package app

import (
	"context"
	"errors"
	"testing"
	"time"
)

type fakeBundleBuilder struct {
	projectRoot string
	entryPoint  string
	bundle      string
	err         error
	calls       int
}

func (f *fakeBundleBuilder) Build(projectRoot, entryPoint string) (string, error) {
	f.calls++
	f.projectRoot = projectRoot
	f.entryPoint = entryPoint
	return f.bundle, f.err
}

type fakeReconciler struct {
	pid       int
	bundle    string
	err       error
	calls     int
	shutdowns int
}

func (f *fakeReconciler) Reconcile(pid int, bundle string) error {
	f.calls++
	f.pid = pid
	f.bundle = bundle
	return f.err
}

func (f *fakeReconciler) Shutdown() error {
	f.shutdowns++
	return nil
}

func TestRunOnceBuildsBundleAndReconcilesPID(t *testing.T) {
	cfg := Config{
		AgentProjectRoot: "/repo/frida/agent",
		AgentEntryPoint:  "/repo/frida/agent/thread-image-graft.ts",
	}
	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}

	if err := RunOnce(cfg, 4321, builder, reconciler); err != nil {
		t.Fatalf("RunOnce returned error: %v", err)
	}

	if builder.projectRoot != cfg.AgentProjectRoot || builder.entryPoint != cfg.AgentEntryPoint {
		t.Fatalf("builder called with (%q, %q), want (%q, %q)", builder.projectRoot, builder.entryPoint, cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	}
	if reconciler.pid != 4321 || reconciler.bundle != "compiled-bundle" {
		t.Fatalf("reconciler called with (%d, %q), want (%d, %q)", reconciler.pid, reconciler.bundle, 4321, "compiled-bundle")
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

func TestRunReturnsOnContextCancellationAfterShutdown(t *testing.T) {
	cfg := Config{
		AgentProjectRoot:       "/repo/frida/agent",
		AgentEntryPoint:        "/repo/frida/agent/thread-image-graft.ts",
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
	}
	ctx, cancel := context.WithCancel(context.Background())
	cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	reconciler := &fakeReconciler{}
	pidFinder := &fakePIDFinder{pid: 4321}

	if err := Run(ctx, cfg, pidFinder, builder, reconciler); err != nil {
		t.Fatalf("Run returned error: %v", err)
	}
	if reconciler.shutdowns != 1 {
		t.Fatalf("Shutdown count = %d, want %d", reconciler.shutdowns, 1)
	}
}

func TestRunRetriesAfterReconcileFailure(t *testing.T) {
	cfg := Config{
		AgentProjectRoot:       "/repo/frida/agent",
		AgentEntryPoint:        "/repo/frida/agent/thread-image-graft.ts",
		DeviceID:               "emulator-5554",
		PidPollIntervalSeconds: 1,
		RetryDelaySeconds:      0,
	}
	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	builder := &fakeBundleBuilder{bundle: "compiled-bundle"}
	pidFinder := &fakePIDFinder{pid: 4321}
	reconciler := &fakeReconciler{err: errors.New("attach failed")}

	done := make(chan error, 1)
	go func() {
		done <- Run(ctx, cfg, pidFinder, builder, reconciler)
	}()

	deadline := time.Now().Add(2 * time.Second)
	for time.Now().Before(deadline) {
		if reconciler.calls >= 2 {
			cancel()
			break
		}
		time.Sleep(10 * time.Millisecond)
	}

	if err := <-done; err != nil {
		t.Fatalf("Run returned error: %v", err)
	}
	if reconciler.calls < 2 {
		t.Fatalf("Reconcile calls = %d, want at least 2", reconciler.calls)
	}
	if builder.calls < 2 {
		t.Fatalf("Build calls = %d, want at least 2", builder.calls)
	}
}
