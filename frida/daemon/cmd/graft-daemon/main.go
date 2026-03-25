package main

import (
	"context"
	"log"
	"os"
	"os/signal"
	"syscall"

	"github.com/park285/Iris/frida/daemon/internal/adb"
	"github.com/park285/Iris/frida/daemon/internal/agentbuild"
	"github.com/park285/Iris/frida/daemon/internal/app"
	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
	"github.com/park285/Iris/frida/daemon/internal/lifecycle"
)

func main() {
	workingDir, err := os.Getwd()
	if err != nil {
		log.Fatal(err)
	}

	repoRoot, err := app.ResolveRepoRoot(workingDir)
	if err != nil {
		log.Fatal(err)
	}

	cfg, err := app.ParseConfig(repoRoot, os.Args[1:])
	if err != nil {
		log.Fatal(err)
	}

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	pidFinder := adb.Service{Runner: adb.ExecRunner{}}
	bundler := agentbuild.NewBundler(cfg.FridaCoreDevkitDir)
	reconciler := lifecycle.NewRunner(fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir))

	if err := app.Run(ctx, cfg, pidFinder, bundler, reconciler); err != nil {
		log.Fatal(err)
	}
}
