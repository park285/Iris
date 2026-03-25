package app

import (
	"context"
	"log"
	"time"
)

type BundleBuilder interface {
	Build(projectRoot, entryPoint string) (string, error)
}

type PIDFinder interface {
	LookupPID(ctx context.Context, deviceID string) (int, error)
}

type Reconciler interface {
	Reconcile(pid int, bundle string) error
	Shutdown() error
}

func RunOnce(cfg Config, pid int, builder BundleBuilder, reconciler Reconciler) error {
	bundle, err := builder.Build(cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	if err != nil {
		return err
	}

	return reconciler.Reconcile(pid, bundle)
}

func Run(ctx context.Context, cfg Config, pidFinder PIDFinder, builder BundleBuilder, reconciler Reconciler) error {
	ticker := time.NewTicker(time.Duration(cfg.PidPollIntervalSeconds) * time.Second)
	defer ticker.Stop()
	defer reconciler.Shutdown()
	retryDelay := time.Duration(cfg.RetryDelaySeconds) * time.Second

	for {
		select {
		case <-ctx.Done():
			return nil
		default:
		}

		pid, err := pidFinder.LookupPID(ctx, cfg.DeviceID)
		if err == nil {
			if err := RunOnce(cfg, pid, builder, reconciler); err != nil {
				log.Printf("reconcile failed for pid=%d agent=%s: %v", pid, cfg.AgentEntryPoint, err)
				select {
				case <-ctx.Done():
					return nil
				case <-time.After(retryDelay):
				}
				continue
			}
			log.Printf("reconcile ok for pid=%d agent=%s", pid, cfg.AgentEntryPoint)
		} else {
			log.Printf("pid lookup failed for device=%s: %v", cfg.DeviceID, err)
		}

		select {
		case <-ctx.Done():
			return nil
		case <-ticker.C:
		}
	}
}
