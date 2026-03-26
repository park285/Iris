package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/park285/Iris/frida/daemon/internal/adb"
	"github.com/park285/Iris/frida/daemon/internal/agentbuild"
	"github.com/park285/Iris/frida/daemon/internal/app"
	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
	"github.com/park285/Iris/frida/daemon/internal/lifecycle"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	workingDir, err := os.Getwd()
	if err != nil {
		return fmt.Errorf("resolve working directory: %w", err)
	}

	repoRoot, err := app.ResolveRepoRoot(workingDir)
	if err != nil {
		return fmt.Errorf("resolve repo root: %w", err)
	}

	cfg, err := app.ParseConfig(repoRoot, os.Args[1:])
	if err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	readiness := app.NewReadinessTracker(app.ReadinessOptions{
		HeartbeatTTL: time.Duration(cfg.HeartbeatTimeoutSeconds) * time.Second,
	})
	if _, err := app.StartReadinessServer(ctx, cfg.ReadinessAddr, readiness); err != nil {
		return fmt.Errorf("start readiness server: %w", err)
	}

	pidFinder := adb.NewService()
	bundler := agentbuild.NewBundler(cfg.FridaCoreDevkitDir)
	runtime := fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir, func(message string) {
		readiness.ObserveScriptMessage(message)
	})
	if !runtime.Available() {
		return fmt.Errorf("frida runtime backend is not available; rebuild with -tags frida_core")
	}
	reconciler := lifecycle.NewRunner(runtime, readiness)

	if err := app.Run(ctx, cfg, pidFinder, bundler, reconciler); err != nil {
		return fmt.Errorf("run daemon: %w", err)
	}

	return nil
}
