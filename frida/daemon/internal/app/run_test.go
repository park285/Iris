package app

import (
	"context"
	"errors"
	"fmt"
	"sync"
	"testing"
	"time"

	"github.com/park285/Iris/frida/daemon/internal/adb"
)

type fakeBundleSource struct {
	mu     sync.Mutex
	bundle string
	err    error
	calls  int
}

func (f *fakeBundleSource) Bundle() (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	return f.bundle, f.err
}

func (f *fakeBundleSource) String() string { return "fake:test" }

func (f *fakeBundleSource) CallCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}

type fakeReconciler struct {
	mu        sync.Mutex
	pid       int
	bundle    string
	err       error
	calls     int
	shutdowns int
}

func (f *fakeReconciler) Reconcile(pid int, bundle string) error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.calls++
	f.pid = pid
	f.bundle = bundle

	return f.err
}

func (f *fakeReconciler) Shutdown() error {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.shutdowns++

	return nil
}

func (f *fakeReconciler) Snapshot() (pid int, bundle string, calls int, shutdowns int) {
	f.mu.Lock()
	defer f.mu.Unlock()

	return f.pid, f.bundle, f.calls, f.shutdowns
}

func (f *fakeReconciler) CallCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()

	return f.calls
}

type fakePIDFinder struct {
	pid   int
	err   error
	calls int
}

func (f *fakePIDFinder) LookupPID(_ context.Context, _ string) (int, error) {
	f.calls++
	return f.pid, f.err
}

type blockingPIDFinder struct {
	started      chan struct{}
	timedOut     chan struct{}
	once         sync.Once
	timedOutOnce sync.Once
}

func (f *blockingPIDFinder) LookupPID(ctx context.Context, _ string) (int, error) {
	f.once.Do(func() {
		close(f.started)
	})

	<-ctx.Done()
	f.timedOutOnce.Do(func() {
		close(f.timedOut)
	})

	return 0, fmt.Errorf("blocking pid finder canceled: %w", ctx.Err())
}

type contextBoundPIDSession struct {
	calls int
}

func (s *contextBoundPIDSession) LookupPID(context.Context) (int, error) {
	s.calls++

	return 4321, nil
}

func (*contextBoundPIDSession) Close() error {
	return nil
}

type contextBoundPIDSessionFactory struct {
	opens int
}

func (f *contextBoundPIDSessionFactory) Open(_ context.Context, _ string) (adb.PIDSession, error) {
	f.opens++

	return &contextBoundPIDSession{}, nil
}

func TestNextPollInterval(t *testing.T) {
	tests := []struct {
		state   ReadinessState
		baseSec int
		want    time.Duration
	}{
		{StateReady, 30, 30 * time.Second},
		{StateBooting, 30, 5 * time.Second},
		{StateHooking, 30, 2 * time.Second},
		{StateDegraded, 30, 2 * time.Second},
		{StateBlocked, 30, 10 * time.Second},
		{StateWarm, 30, 5 * time.Second},
		{StateReady, 1, 1 * time.Second},
		{StateBooting, 1, 1 * time.Second},
		{StateReady, 0, 1 * time.Second},
		{StateBooting, 0, 1 * time.Second},
	}

	for _, tt := range tests {
		got := nextPollInterval(tt.state, tt.baseSec)
		if got != tt.want {
			t.Errorf("nextPollInterval(%q, %d) = %s, want %s", tt.state, tt.baseSec, got, tt.want)
		}
	}
}

func TestRunReturnsOnContextCancellationAfterShutdown(t *testing.T) {
	cfg := Config{
		AgentName:              "test",
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	source := &fakeBundleSource{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}
	pidFinder := &fakePIDFinder{pid: 4321}

	if err := Run(ctx, cfg, pidFinder, reconciler, func() ReadinessState { return StateReady }, source); err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	_, _, _, shutdowns := reconciler.Snapshot()
	if shutdowns != 1 {
		t.Fatalf("Shutdown count = %d, want %d", shutdowns, 1)
	}
}

func TestRunRetriesAfterReconcileFailure(t *testing.T) {
	cfg := Config{
		AgentName:              "test",
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	source := &fakeBundleSource{bundle: "compiled-bundle"}
	pidFinder := &fakePIDFinder{pid: 4321}
	reconciler := &fakeReconciler{err: errors.New("attach failed")}

	done := make(chan error, 1)

	go func() {
		done <- Run(ctx, cfg, pidFinder, reconciler, func() ReadinessState { return StateReady }, source)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if reconciler.CallCount() >= 2 {
			cancel()
			break
		}

		time.Sleep(10 * time.Millisecond)
	}

	if err := <-done; err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	_, _, reconcileCalls, _ := reconciler.Snapshot()
	if reconcileCalls < 2 {
		t.Fatalf("Reconcile calls = %d, want at least 2", reconcileCalls)
	}
}

func TestRunUsesBoundedPIDLookupContext(t *testing.T) {
	cfg := Config{
		AgentName:              "test",
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	source := &fakeBundleSource{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}
	pidFinder := &blockingPIDFinder{
		started:  make(chan struct{}),
		timedOut: make(chan struct{}),
	}

	done := make(chan error, 1)

	go func() {
		done <- Run(ctx, cfg, pidFinder, reconciler, func() ReadinessState { return StateReady }, source)
	}()

	select {
	case <-pidFinder.started:
	case <-time.After(250 * time.Millisecond):
		t.Fatal("LookupPID did not start in time")
	}

	select {
	case <-pidFinder.timedOut:
	case <-time.After(750 * time.Millisecond):
		t.Fatal("LookupPID context was not bounded by a timeout")
	}

	cancel()

	if err := <-done; err != nil {
		t.Fatalf("Run returned error: %v", err)
	}
}

func TestLookupPIDWithTimeoutKeepsReusablePIDSessionAlive(t *testing.T) {
	factory := &contextBoundPIDSessionFactory{}
	service := &adb.Service{Factory: factory}
	cfg := Config{
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
	}

	for range 2 {
		pid, err := lookupPIDWithTimeout(t.Context(), cfg, service)
		if err != nil {
			t.Fatalf("lookupPIDWithTimeout returned error: %v", err)
		}

		if pid != 4321 {
			t.Fatalf("lookupPIDWithTimeout = %d, want %d", pid, 4321)
		}
	}

	if factory.opens != 1 {
		t.Fatalf("factory opens = %d, want %d", factory.opens, 1)
	}
}
