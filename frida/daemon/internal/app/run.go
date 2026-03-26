package app

import (
	"context"
	"fmt"
	"log"
	"time"
)

const (
	minPIDLookupTimeout = 250 * time.Millisecond
	maxPIDLookupTimeout = 5 * time.Second
	minRetryDelay       = 25 * time.Millisecond
)

type BundleBuilder interface {
	Build(ctx context.Context, projectRoot, entryPoint string) (string, error)
}

type PIDFinder interface {
	LookupPID(ctx context.Context, deviceID string) (int, error)
}

type Reconciler interface {
	Reconcile(pid int, bundle string) error
	Shutdown() error
}

type bundleCache struct {
	ready         bool
	bundle        string
	sourceVersion uint64
	lastError     error
	errorVersion  uint64
}

func (c *bundleCache) get(ctx context.Context, cfg Config, builder BundleBuilder, versioner sourceVersioner) (string, error) {
	sourceVersion := versioner.Version()

	if c.ready && c.sourceVersion == sourceVersion {
		return c.bundle, nil
	}

	if c.lastError != nil && c.errorVersion == sourceVersion {
		return "", c.lastError
	}

	bundle, err := builder.Build(ctx, cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	if err != nil {
		c.lastError = fmt.Errorf("build agent bundle: %w", err)
		c.errorVersion = sourceVersion
		c.ready = false
		return "", c.lastError
	}

	c.bundle = bundle
	c.ready = true
	c.sourceVersion = sourceVersion
	c.lastError = nil

	return c.bundle, nil
}

func pidLookupTimeout(cfg Config) time.Duration {
	pollInterval := time.Duration(cfg.PidPollIntervalSeconds) * time.Second
	if pollInterval <= 0 {
		return minPIDLookupTimeout
	}

	timeout := pollInterval / 2

	return min(max(timeout, minPIDLookupTimeout), maxPIDLookupTimeout)
}

func nextPollInterval(state ReadinessState, baseSeconds int) time.Duration {
	base := max(time.Duration(baseSeconds)*time.Second, 1*time.Second)

	switch state {
	case StateReady:
		return base
	case StateBooting:
		return min(5*time.Second, base)
	case StateHooking:
		return min(2*time.Second, base)
	case StateDegraded:
		return min(2*time.Second, base)
	case StateBlocked:
		return min(10*time.Second, base)
	case StateWarm:
		return min(5*time.Second, base)
	default:
		return min(5*time.Second, base)
	}
}

func lookupPIDWithTimeout(ctx context.Context, cfg Config, pidFinder PIDFinder) (int, error) {
	lookupCtx, cancelLookup := context.WithTimeout(ctx, pidLookupTimeout(cfg))
	defer cancelLookup()

	pid, err := pidFinder.LookupPID(lookupCtx, cfg.DeviceID)
	if err != nil {
		return 0, fmt.Errorf("lookup pid with timeout: %w", err)
	}

	return pid, nil
}

func RunOnce(ctx context.Context, cfg Config, pid int, builder BundleBuilder, reconciler Reconciler) error {
	bundle, err := builder.Build(ctx, cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	if err != nil {
		return fmt.Errorf("build agent bundle: %w", err)
	}

	if err := reconciler.Reconcile(pid, bundle); err != nil {
		return fmt.Errorf("reconcile pid %d: %w", pid, err)
	}

	return nil
}

func Run(ctx context.Context, cfg Config, pidFinder PIDFinder, builder BundleBuilder, reconciler Reconciler, stateFunc func() ReadinessState) error {
	versioner, err := newSourceVersioner(cfg.AgentProjectRoot)
	if err != nil {
		return fmt.Errorf("watch agent sources: %w", err)
	}

	defer func() {
		if err := versioner.Close(); err != nil {
			log.Printf("source versioner shutdown failed: %v", err)
		}
	}()
	defer func() {
		if closer, ok := pidFinder.(interface{ Close() error }); ok {
			if err := closer.Close(); err != nil {
				log.Printf("pid finder shutdown failed: %v", err)
			}
		}
	}()

	if err := runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, versioner, stateFunc); err != nil {
		return fmt.Errorf("run with source versioner: %w", err)
	}

	return nil
}

func runWithSourceVersioner(
	ctx context.Context,
	cfg Config,
	pidFinder PIDFinder,
	builder BundleBuilder,
	reconciler Reconciler,
	versioner sourceVersioner,
	stateFunc func() ReadinessState,
) error {
	defer func() {
		if err := reconciler.Shutdown(); err != nil {
			log.Printf("reconciler shutdown failed: %v", err)
		}
	}()

	if stateFunc == nil {
		stateFunc = func() ReadinessState { return StateBooting }
	}

	retryDelay := max(time.Duration(cfg.RetryDelaySeconds)*time.Second, minRetryDelay)

	cache := &bundleCache{}

	pollTimer := time.NewTimer(0)
	defer pollTimer.Stop()

	var lastPID int
	var lastBundle string
	lastState := ReadinessState("")

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-pollTimer.C:
		}

		pid, err := lookupPIDWithTimeout(ctx, cfg, pidFinder)
		if err != nil {
			log.Printf("pid lookup failed for device=%s: %v", cfg.DeviceID, err)
			pollTimer.Reset(retryDelay)
			continue
		}

		bundle, bundleErr := cache.get(ctx, cfg, builder, versioner)
		if bundleErr != nil {
			log.Printf("bundle build failed for agent=%s: %v", cfg.AgentEntryPoint, bundleErr)
			pollTimer.Reset(retryDelay)
			continue
		}

		if runErr := reconciler.Reconcile(pid, bundle); runErr != nil {
			log.Printf("reconcile failed for pid=%d agent=%s: %v", pid, cfg.AgentEntryPoint, runErr)
			pollTimer.Reset(retryDelay)
			continue
		}

		if pid != lastPID {
			log.Printf("attached pid=%d agent=%s", pid, cfg.AgentEntryPoint)
			lastPID = pid
			lastBundle = bundle
		} else if bundle != lastBundle {
			log.Printf("reloaded bundle for pid=%d agent=%s", pid, cfg.AgentEntryPoint)
			lastBundle = bundle
		}

		state := stateFunc()
		if state != lastState {
			if lastState == "" {
				log.Printf("state=%s pid=%d", state, pid)
			} else {
				log.Printf("state %s -> %s pid=%d", lastState, state, pid)
			}
			lastState = state
		}

		pollTimer.Reset(nextPollInterval(state, cfg.PidPollIntervalSeconds))
	}
}
