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
	"github.com/park285/Iris/frida/daemon/internal/agentbundle"
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
	cfg, err := app.ParseConfig(os.Args[1:])
	if err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	var source app.BundleSource
	if cfg.AgentOverride != "" {
		if _, err := os.Stat(cfg.AgentOverride); err != nil {
			return fmt.Errorf("agent override file: %w", err)
		}
		source = app.NewFileBundleSource(cfg.AgentOverride)
	} else {
		s, err := agentbundle.Source(cfg.AgentName)
		if err != nil {
			return fmt.Errorf("resolve agent bundle: %w", err)
		}
		source = s
	}

	log.Printf("agent=%s source=%s", cfg.AgentName, source)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	readiness := app.NewReadinessTracker(app.ReadinessOptions{
		HeartbeatTTL: time.Duration(cfg.HeartbeatTimeoutSeconds) * time.Second,
	})
	if _, err := app.StartReadinessServer(ctx, cfg.ReadinessAddr, readiness); err != nil {
		return fmt.Errorf("start readiness server: %w", err)
	}

	pidFinder := adb.NewService()
	runtime := fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir, func(message string) {
		readiness.ObserveScriptMessage(message)
	})
	if !runtime.Available() {
		return fmt.Errorf("frida runtime backend is not available; rebuild with -tags frida_core")
	}
	reconciler := lifecycle.NewRunner(runtime, readiness)

	if err := app.Run(ctx, cfg, pidFinder, reconciler, readiness.CurrentState, source); err != nil {
		return fmt.Errorf("run daemon: %w", err)
	}

	return nil
}
