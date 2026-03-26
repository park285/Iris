package app

import (
	"context"
	"errors"
	"fmt"
	"os"
	"path/filepath"
	"sync"
	"testing"
	"time"

	"github.com/park285/Iris/frida/daemon/internal/adb"
)

type fakeBundleBuilder struct {
	mu          sync.Mutex
	projectRoot string
	entryPoint  string
	bundle      string
	err         error
	calls       int
}

func (f *fakeBundleBuilder) Build(_ context.Context, projectRoot, entryPoint string) (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.calls++

	f.projectRoot = projectRoot
	f.entryPoint = entryPoint

	return f.bundle, f.err
}

func (f *fakeBundleBuilder) Snapshot() (projectRoot, entryPoint string, calls int) {
	f.mu.Lock()
	defer f.mu.Unlock()

	return f.projectRoot, f.entryPoint, f.calls
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

func writeTempAgentProject(t *testing.T) (string, string) {
	t.Helper()

	agentRoot := t.TempDir()

	entryPoint := filepath.Join(agentRoot, "thread-image-graft.ts")
	if err := os.WriteFile(entryPoint, []byte("export const version = 1;\n"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	return agentRoot, entryPoint
}

func TestRunOnceBuildsBundleAndReconcilesPID(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	cfg := Config{
		AgentProjectRoot: agentRoot,
		AgentEntryPoint:  entryPoint,
	}
	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}

	if err := RunOnce(t.Context(), cfg, 4321, builder, reconciler); err != nil {
		t.Fatalf("RunOnce returned error: %v", err)
	}

	projectRoot, entryPoint, _ := builder.Snapshot()
	if projectRoot != cfg.AgentProjectRoot || entryPoint != cfg.AgentEntryPoint {
		t.Fatalf("builder called with (%q, %q), want (%q, %q)", projectRoot, entryPoint, cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	}

	pid, bundle, _, _ := reconciler.Snapshot()
	if pid != 4321 || bundle != "compiled-bundle" {
		t.Fatalf("reconciler called with (%d, %q), want (%d, %q)", pid, bundle, 4321, "compiled-bundle")
	}
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
	started  chan struct{}
	timedOut chan struct{}
	once     sync.Once
}

func (f *blockingPIDFinder) LookupPID(ctx context.Context, _ string) (int, error) {
	f.once.Do(func() {
		close(f.started)
	})

	<-ctx.Done()
	close(f.timedOut)

	return 0, fmt.Errorf("blocking pid finder canceled: %w", ctx.Err())
}

type contextBoundPIDSession struct {
	openCtx context.Context
	calls   int
}

func (s *contextBoundPIDSession) LookupPID(context.Context) (int, error) {
	s.calls++

	if s.calls > 1 {
		select {
		case <-s.openCtx.Done():
			return 0, errors.New("session context canceled")
		default:
		}
	}

	return 4321, nil
}

func (*contextBoundPIDSession) Close() error {
	return nil
}

type contextBoundPIDSessionFactory struct {
	opens int
}

func (f *contextBoundPIDSessionFactory) Open(ctx context.Context, _ string) (adb.PIDSession, error) {
	f.opens++

	return &contextBoundPIDSession{openCtx: ctx}, nil
}

type fakeSourceVersioner struct {
	mu      sync.Mutex
	version uint64
}

func (f *fakeSourceVersioner) Version() uint64 {
	f.mu.Lock()
	defer f.mu.Unlock()

	return f.version
}

func (f *fakeSourceVersioner) Advance() {
	f.mu.Lock()
	defer f.mu.Unlock()

	f.version++
}

func (*fakeSourceVersioner) Close() error {
	return nil
}

func TestBundleCacheSuppressesRebuildForCachedError(t *testing.T) {
	builder := &fakeBundleBuilder{err: errors.New("compile error")}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	_, err1 := cache.get(t.Context(), cfg, builder, versioner)
	if err1 == nil {
		t.Fatal("expected error on first build")
	}

	_, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 == nil {
		t.Fatal("expected cached error on second build")
	}

	_, _, buildCalls := builder.Snapshot()
	if buildCalls != 1 {
		t.Fatalf("Build calls = %d, want 1 (error should be cached)", buildCalls)
	}
}

func TestBundleCacheRetryBuildAfterSourceChange(t *testing.T) {
	builder := &fakeBundleBuilder{err: errors.New("compile error")}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	_, err1 := cache.get(t.Context(), cfg, builder, versioner)
	if err1 == nil {
		t.Fatal("expected error on first build")
	}

	builder.mu.Lock()
	builder.err = nil
	builder.bundle = "fixed-bundle"
	builder.mu.Unlock()
	versioner.Advance()

	bundle, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 != nil {
		t.Fatalf("expected success after source change: %v", err2)
	}

	if bundle != "fixed-bundle" {
		t.Fatalf("bundle = %q, want %q", bundle, "fixed-bundle")
	}

	_, _, buildCalls := builder.Snapshot()
	if buildCalls != 2 {
		t.Fatalf("Build calls = %d, want 2", buildCalls)
	}
}

func TestBundleCacheDoesNotReturnStaleBundleAfterBuildError(t *testing.T) {
	builder := &fakeBundleBuilder{bundle: "good-bundle"}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	bundle, err := cache.get(t.Context(), cfg, builder, versioner)
	if err != nil {
		t.Fatalf("first build: %v", err)
	}

	if bundle != "good-bundle" {
		t.Fatalf("bundle = %q, want %q", bundle, "good-bundle")
	}

	builder.mu.Lock()
	builder.err = errors.New("compile error")
	builder.bundle = ""
	builder.mu.Unlock()
	versioner.Advance()

	_, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 == nil {
		t.Fatal("expected error after source change + build failure, not stale bundle")
	}
}

func TestRunReturnsOnContextCancellationAfterShutdown(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	cfg := Config{
		AgentProjectRoot:       agentRoot,
		AgentEntryPoint:        entryPoint,
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
	}
	ctx, cancel := context.WithCancel(t.Context())
	cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}
	pidFinder := &fakePIDFinder{pid: 4321}

	if err := Run(ctx, cfg, pidFinder, builder, reconciler); err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	_, _, _, shutdowns := reconciler.Snapshot()
	if shutdowns != 1 {
		t.Fatalf("Shutdown count = %d, want %d", shutdowns, 1)
	}
}

func TestRunRetriesAfterReconcileFailure(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	cfg := Config{
		AgentProjectRoot:       agentRoot,
		AgentEntryPoint:        entryPoint,
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	pidFinder := &fakePIDFinder{pid: 4321}
	reconciler := &fakeReconciler{err: errors.New("attach failed")}

	done := make(chan error, 1)

	go func() {
		done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1})
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

	_, _, buildCalls := builder.Snapshot()
	if buildCalls != 1 {
		t.Fatalf("Build calls = %d, want %d", buildCalls, 1)
	}
}

func TestRunUsesBoundedPIDLookupContext(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	cfg := Config{
		AgentProjectRoot:       agentRoot,
		AgentEntryPoint:        entryPoint,
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}
	pidFinder := &blockingPIDFinder{
		started:  make(chan struct{}),
		timedOut: make(chan struct{}),
	}

	done := make(chan error, 1)

	go func() {
		done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1})
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

func TestWaitRetryZeroDelayStillYields(t *testing.T) {
	timer := time.NewTimer(time.Hour)
	if !timer.Stop() {
		<-timer.C
	}

	start := time.Now()

	if !waitRetry(t.Context(), 0, timer) {
		t.Fatal("waitRetry returned false, want true")
	}

	if elapsed := time.Since(start); elapsed < 20*time.Millisecond {
		t.Fatalf("waitRetry elapsed = %s, want at least 20ms yield", elapsed)
	}
}

func TestRunRebuildsBundleAfterAgentSourceChanges(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	cfg := Config{
		AgentProjectRoot:       agentRoot,
		AgentEntryPoint:        entryPoint,
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}

	ctx, cancel := context.WithCancel(t.Context())
	defer cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	pidFinder := &fakePIDFinder{pid: 4321}
	reconciler := &fakeReconciler{err: errors.New("attach failed")}
	sourceVersioner := &fakeSourceVersioner{version: 1}

	done := make(chan error, 1)

	go func() {
		done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, sourceVersioner)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		_, _, buildCalls := builder.Snapshot()
		if buildCalls >= 1 {
			break
		}

		time.Sleep(10 * time.Millisecond)
	}

	if err := os.WriteFile(entryPoint, []byte("export const version = 2;\n"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	sourceVersioner.Advance()

	deadline = time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		_, _, buildCalls := builder.Snapshot()
		if buildCalls >= 2 {
			cancel()
			break
		}

		time.Sleep(10 * time.Millisecond)
	}

	if err := <-done; err != nil {
		t.Fatalf("Run returned error: %v", err)
	}

	_, _, buildCalls := builder.Snapshot()
	if buildCalls < 2 {
		t.Fatalf("Build calls = %d, want at least 2 after source change", buildCalls)
	}
}

func TestSourceVersionerBumpsAfterRelevantFileChange(t *testing.T) {
	agentRoot, entryPoint := writeTempAgentProject(t)

	versioner, err := newSourceVersioner(agentRoot)
	if err != nil {
		t.Fatalf("newSourceVersioner returned error: %v", err)
	}

	defer func() {
		if closeErr := versioner.Close(); closeErr != nil {
			t.Fatalf("Close returned error: %v", closeErr)
		}
	}()

	initialVersion := versioner.Version()

	if err := os.WriteFile(entryPoint, []byte("export const version = 2;\n"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if versioner.Version() > initialVersion {
			return
		}

		time.Sleep(10 * time.Millisecond)
	}

	t.Fatalf("Version = %d, want greater than %d after source change", versioner.Version(), initialVersion)
}
