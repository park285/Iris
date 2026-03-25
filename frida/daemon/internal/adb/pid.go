package adb

import (
	"context"
	"errors"
	"os/exec"
	"strconv"
	"strings"
)

type CommandRunner interface {
	Run(ctx context.Context, name string, args ...string) (string, error)
}

type ExecRunner struct{}

func (ExecRunner) Run(ctx context.Context, name string, args ...string) (string, error) {
	cmd := exec.CommandContext(ctx, name, args...)
	output, err := cmd.Output()
	if err != nil {
		return "", err
	}
	return string(output), nil
}

type Service struct {
	Runner CommandRunner
}

func ParsePID(raw string) (int, error) {
	trimmed := strings.TrimSpace(raw)
	if trimmed == "" {
		return 0, errors.New("pid output is empty")
	}
	pid, err := strconv.Atoi(trimmed)
	if err != nil {
		return 0, err
	}
	return pid, nil
}

func LookupPID(ctx context.Context, runner CommandRunner, deviceID string) (int, error) {
	stdout, err := runner.Run(ctx, "adb", "-s", deviceID, "shell", "pidof com.kakao.talk")
	if err != nil {
		return 0, err
	}
	return ParsePID(stdout)
}

func (s Service) LookupPID(ctx context.Context, deviceID string) (int, error) {
	return LookupPID(ctx, s.Runner, deviceID)
}
