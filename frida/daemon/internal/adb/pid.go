//nolint:depguard // Process spawning is intentionally isolated in the adb adapter.
package adb

import (
	"bufio"
	"context"
	"errors"
	"fmt"
	"io"
	"os"
	"os/exec"
	"strconv"
	"strings"
	"sync"
)

var ErrPIDNotFound = errors.New("pid output is empty")

type CommandRunner interface {
	Run(ctx context.Context, args ...string) (string, error)
}

type ExecRunner struct{}

func (ExecRunner) Run(ctx context.Context, args ...string) (string, error) {
	//nolint:gosec // The daemon always invokes the fixed adb binary with fixed subcommands.
	cmd := exec.CommandContext(ctx, "adb", args...)

	output, err := cmd.Output()
	if err != nil {
		return "", fmt.Errorf("run adb: %w", err)
	}

	return string(output), nil
}

type PIDSession interface {
	LookupPID(ctx context.Context) (int, error)
	Close() error
}

type SessionFactory interface {
	Open(ctx context.Context, deviceID string) (PIDSession, error)
}

type ExecSessionFactory struct{}

type execPIDSession struct {
	cmd       *exec.Cmd
	stdin     io.WriteCloser
	stdout    *bufio.Reader
	mu        sync.Mutex
	nextToken uint64

	closeOnce sync.Once
	closeErr  error
}

type lineReader interface {
	ReadString(delim byte) (string, error)
}

func (ExecSessionFactory) Open(ctx context.Context, deviceID string) (PIDSession, error) {
	session, err := newExecPIDSession(ctx, deviceID)
	if err != nil {
		return nil, fmt.Errorf("open exec pid session for device %q: %w", deviceID, err)
	}

	return session, nil
}

type Service struct {
	Factory SessionFactory

	mu       sync.Mutex
	sessions map[string]PIDSession
}

func ParsePID(raw string) (int, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return 0, ErrPIDNotFound
	}

	pid, err := strconv.Atoi(trimmed)
	if err != nil {
		return 0, fmt.Errorf("parse pid %q: %w", trimmed, err)
	}

	return pid, nil
}

func NewService() *Service {
	return &Service{
		Factory: ExecSessionFactory{},
	}
}

func LookupPID(ctx context.Context, runner CommandRunner, deviceID string) (int, error) {
	stdout, err := runner.Run(ctx, "-s", deviceID, "shell", "pidof com.kakao.talk")
	if err != nil {
		return 0, fmt.Errorf("lookup pid for device %q: %w", deviceID, err)
	}

	pid, err := ParsePID(stdout)
	if err != nil {
		return 0, fmt.Errorf("parse pid output for device %q: %w", deviceID, err)
	}

	return pid, nil
}

func newExecPIDSession(ctx context.Context, deviceID string) (PIDSession, error) {
	// Process lifetime is independent of the caller's context (session is reused across calls).
	// Close() manages process cleanup via Kill()+Wait().
	//nolint:gosec // The daemon always invokes the fixed adb binary with fixed subcommands.
	cmd := exec.Command("adb", "-s", deviceID, "shell", "sh")

	stdin, err := cmd.StdinPipe()
	if err != nil {
		return nil, fmt.Errorf("open adb shell stdin: %w", err)
	}

	stdout, err := cmd.StdoutPipe()
	if err != nil {
		return nil, fmt.Errorf("open adb shell stdout: %w", err)
	}

	cmd.Stderr = io.Discard

	if err := ctx.Err(); err != nil {
		return nil, fmt.Errorf("start adb shell: %w", err)
	}

	if err := cmd.Start(); err != nil {
		return nil, fmt.Errorf("start adb shell: %w", err)
	}

	return &execPIDSession{
		cmd:    cmd,
		stdin:  stdin,
		stdout: bufio.NewReader(stdout),
	}, nil
}

func (s *execPIDSession) LookupPID(ctx context.Context) (int, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	select {
	case <-ctx.Done():
		return 0, fmt.Errorf("lookup pid canceled: %w", ctx.Err())
	default:
	}

	marker := fmt.Sprintf("__IRIS_PID_END__:%d", s.nextToken)
	s.nextToken++

	query := fmt.Sprintf("pidof com.kakao.talk 2>/dev/null; printf '%s\\n'\n", marker)
	if _, err := io.WriteString(s.stdin, query); err != nil {
		return 0, fmt.Errorf("write pid query: %w", err)
	}

	lines, err := readUntilMarkerWithContext(ctx, s.stdout, marker, s.Close)
	if err != nil {
		return 0, fmt.Errorf("read pid session response: %w", err)
	}

	pid, err := ParsePID(strings.Join(lines, "\n"))
	if err != nil {
		return 0, fmt.Errorf("parse pid from session response: %w", err)
	}

	return pid, nil
}

func (s *execPIDSession) Close() error {
	s.closeOnce.Do(func() {
		var closeErrs []error

		if err := s.stdin.Close(); err != nil && !errors.Is(err, os.ErrClosed) {
			closeErrs = append(closeErrs, fmt.Errorf("close adb shell stdin: %w", err))
		}

		if s.cmd.Process != nil {
			if err := s.cmd.Process.Kill(); err != nil && !errors.Is(err, os.ErrProcessDone) {
				closeErrs = append(closeErrs, fmt.Errorf("kill adb shell: %w", err))
			}
		}

		if err := s.cmd.Wait(); err != nil {
			var exitErr *exec.ExitError
			if !errors.As(err, &exitErr) {
				closeErrs = append(closeErrs, fmt.Errorf("wait adb shell: %w", err))
			}
		}

		s.closeErr = errors.Join(closeErrs...)
	})

	return s.closeErr
}

func readUntilMarkerWithContext(
	ctx context.Context,
	reader lineReader,
	marker string,
	cancel func() error,
) ([]string, error) {
	type readResult struct {
		lines []string
		err   error
	}

	resultCh := make(chan readResult, 1)

	go func() {
		lines, err := readUntilMarker(reader, marker)
		resultCh <- readResult{lines: lines, err: err}
	}()

	select {
	case result := <-resultCh:
		if result.err != nil {
			return nil, fmt.Errorf("read marker %q: %w", marker, result.err)
		}

		return result.lines, nil
	case <-ctx.Done():
		cancelErr := cancel()
		result := <-resultCh

		return nil, errors.Join(
			fmt.Errorf("lookup pid canceled: %w", ctx.Err()),
			cancelErr,
			result.err,
		)
	}
}

func readUntilMarker(reader lineReader, marker string) ([]string, error) {
	lines := make([]string, 0, 1)

	for {
		line, err := reader.ReadString('\n')
		if err != nil {
			return nil, fmt.Errorf("read adb shell response: %w", err)
		}

		trimmed := strings.TrimSpace(line)
		if trimmed == marker {
			return lines, nil
		}

		if trimmed != "" {
			lines = append(lines, trimmed)
		}
	}
}

func (s *Service) LookupPID(ctx context.Context, deviceID string) (int, error) {
	session, err := s.getSession(ctx, deviceID)
	if err != nil {
		return 0, fmt.Errorf("service lookup pid for device %q: %w", deviceID, err)
	}

	pid, err := session.LookupPID(ctx)
	if err == nil {
		return pid, nil
	}

	if errors.Is(err, ErrPIDNotFound) {
		return 0, fmt.Errorf("service lookup pid for device %q: %w", deviceID, err)
	}

	s.dropSession(deviceID, session)

	if errors.Is(err, context.Canceled) || errors.Is(err, context.DeadlineExceeded) {
		return 0, fmt.Errorf("service lookup pid for device %q: %w", deviceID, err)
	}

	session, openErr := s.getSession(ctx, deviceID)
	if openErr != nil {
		return 0, fmt.Errorf("service lookup pid for device %q: %w", deviceID, openErr)
	}

	pid, retryErr := session.LookupPID(ctx)
	if retryErr != nil {
		s.dropSession(deviceID, session)
		return 0, fmt.Errorf("service lookup pid for device %q: %w", deviceID, retryErr)
	}

	return pid, nil
}

func (s *Service) Close() error {
	s.mu.Lock()

	sessions := make([]PIDSession, 0, len(s.sessions))
	for _, session := range s.sessions {
		sessions = append(sessions, session)
	}

	s.sessions = nil
	s.mu.Unlock()

	var closeErrs []error

	for _, session := range sessions {
		if err := session.Close(); err != nil {
			closeErrs = append(closeErrs, err)
		}
	}

	return errors.Join(closeErrs...)
}

func (s *Service) getSession(ctx context.Context, deviceID string) (PIDSession, error) {
	s.mu.Lock()
	defer s.mu.Unlock()

	if s.sessions == nil {
		s.sessions = make(map[string]PIDSession)
	}

	if session, ok := s.sessions[deviceID]; ok {
		return session, nil
	}

	factory := s.Factory
	if factory == nil {
		factory = ExecSessionFactory{}
	}

	session, err := factory.Open(ctx, deviceID)
	if err != nil {
		return nil, fmt.Errorf("open pid session for device %q: %w", deviceID, err)
	}

	s.sessions[deviceID] = session

	return session, nil
}

func (s *Service) dropSession(deviceID string, expected PIDSession) {
	s.mu.Lock()

	session, ok := s.sessions[deviceID]
	if ok && session == expected {
		delete(s.sessions, deviceID)
	}

	s.mu.Unlock()

	if ok && session == expected {
		_ = session.Close()
	}
}
