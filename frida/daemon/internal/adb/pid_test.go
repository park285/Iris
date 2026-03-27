package adb

import (
	"context"
	"errors"
	"io"
	"sync"
	"testing"
	"time"
)

func TestParsePID(t *testing.T) {
	tests := []struct {
		name    string
		input   string
		want    int
		wantErr bool
	}{
		{name: "simple", input: "1234\n", want: 1234},
		{name: "trimmed", input: " 98765 ", want: 98765},
		{name: "empty", input: "", wantErr: true},
		{name: "invalid", input: "abc", wantErr: true},
	}

	for _, tc := range tests {
		t.Run(tc.name, func(t *testing.T) {
			got, err := ParsePID(tc.input)
			if tc.wantErr {
				if err == nil {
					t.Fatal("expected error")
				}

				return
			}

			if err != nil {
				t.Fatalf("ParsePID returned error: %v", err)
			}

			if got != tc.want {
				t.Fatalf("ParsePID = %d, want %d", got, tc.want)
			}
		})
	}
}

type fakeCommandRunner struct {
	args   []string
	stdout string
	err    error
}

func (f *fakeCommandRunner) Run(_ context.Context, args ...string) (string, error) {
	f.args = append([]string{"adb"}, args...)
	return f.stdout, f.err
}

func TestLookupPIDUsesADBPidof(t *testing.T) {
	runner := &fakeCommandRunner{stdout: "1234\n"}

	pid, err := LookupPID(t.Context(), runner, "emulator-5554")
	if err != nil {
		t.Fatalf("LookupPID returned error: %v", err)
	}

	if pid != 1234 {
		t.Fatalf("LookupPID = %d, want %d", pid, 1234)
	}

	wantArgs := []string{"adb", "-s", "emulator-5554", "shell", "pidof com.kakao.talk"}
	if len(runner.args) != len(wantArgs) {
		t.Fatalf("args = %v, want %v", runner.args, wantArgs)
	}

	for i := range wantArgs {
		if runner.args[i] != wantArgs[i] {
			t.Fatalf("args = %v, want %v", runner.args, wantArgs)
		}
	}
}

type fakePIDSession struct {
	pid   int
	err   error
	calls int
}

func (f *fakePIDSession) LookupPID(context.Context) (int, error) {
	f.calls++

	return f.pid, f.err
}

func (f *fakePIDSession) Close() error {
	return nil
}

type fakePIDSessionFactory struct {
	sessions []*fakePIDSession
	opens    int
}

func (f *fakePIDSessionFactory) Open(_ context.Context, _ string) (PIDSession, error) {
	if f.opens >= len(f.sessions) {
		return nil, errors.New("no session left")
	}

	session := f.sessions[f.opens]
	f.opens++

	return session, nil
}

func TestServiceReusesPIDSessionAcrossLookups(t *testing.T) {
	session := &fakePIDSession{pid: 1234}
	service := &Service{
		Factory: &fakePIDSessionFactory{
			sessions: []*fakePIDSession{session},
		},
	}

	for range 2 {
		pid, err := service.LookupPID(t.Context(), "emulator-5554")
		if err != nil {
			t.Fatalf("LookupPID returned error: %v", err)
		}

		if pid != 1234 {
			t.Fatalf("LookupPID = %d, want %d", pid, 1234)
		}
	}

	if session.calls != 2 {
		t.Fatalf("session calls = %d, want %d", session.calls, 2)
	}

	if err := service.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
}

func TestServiceReopensPIDSessionAfterLookupFailure(t *testing.T) {
	service := &Service{
		Factory: &fakePIDSessionFactory{
			sessions: []*fakePIDSession{
				{err: errors.New("broken session")},
				{pid: 4321},
			},
		},
	}

	pid, err := service.LookupPID(t.Context(), "emulator-5554")
	if err != nil {
		t.Fatalf("LookupPID returned error: %v", err)
	}

	if pid != 4321 {
		t.Fatalf("LookupPID = %d, want %d", pid, 4321)
	}

	if err := service.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
}

func TestServiceKeepsPIDSessionWhenPIDIsMissing(t *testing.T) {
	session := &fakePIDSession{err: ErrPIDNotFound}
	factory := &fakePIDSessionFactory{
		sessions: []*fakePIDSession{session},
	}
	service := &Service{Factory: factory}

	for range 2 {
		_, err := service.LookupPID(t.Context(), "emulator-5554")
		if !errors.Is(err, ErrPIDNotFound) {
			t.Fatalf("LookupPID error = %v, want ErrPIDNotFound", err)
		}
	}

	if session.calls != 2 {
		t.Fatalf("session calls = %d, want %d", session.calls, 2)
	}

	if factory.opens != 1 {
		t.Fatalf("factory opens = %d, want %d", factory.opens, 1)
	}

	if err := service.Close(); err != nil {
		t.Fatalf("Close returned error: %v", err)
	}
}

type blockingLineReader struct {
	ch chan struct{}
}

func (r *blockingLineReader) ReadString(byte) (string, error) {
	<-r.ch
	return "", io.EOF
}

func TestReadUntilMarkerWithContextCancelsBlockedRead(t *testing.T) {
	reader := &blockingLineReader{ch: make(chan struct{})}

	var closeOnce sync.Once

	cancelReader := func() error {
		closeOnce.Do(func() {
			close(reader.ch)
		})

		return nil
	}

	ctx, cancel := context.WithTimeout(t.Context(), 10*time.Millisecond)
	defer cancel()

	start := time.Now()
	_, err := readUntilMarkerWithContext(ctx, reader, "__IRIS_PID_END__:0", cancelReader)
	elapsed := time.Since(start)

	if !errors.Is(err, context.DeadlineExceeded) {
		t.Fatalf("readUntilMarkerWithContext error = %v, want context deadline exceeded", err)
	}

	if elapsed > 200*time.Millisecond {
		t.Fatalf("readUntilMarkerWithContext took %s, want prompt cancellation", elapsed)
	}
}
