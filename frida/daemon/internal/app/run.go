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

type PIDFinder interface {
	LookupPID(ctx context.Context, deviceID string) (int, error)
}

type Reconciler interface {
	Reconcile(pid int, bundle string) error
	Shutdown() error
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

func Run(ctx context.Context, cfg Config, pidFinder PIDFinder,
	reconciler Reconciler, stateFunc func() ReadinessState,
	source BundleSource) error {

	defer func() {
		if closer, ok := pidFinder.(interface{ Close() error }); ok {
			if err := closer.Close(); err != nil {
				log.Printf("pid finder shutdown failed: %v", err)
			}
		}
	}()

	defer func() {
		if err := reconciler.Shutdown(); err != nil {
			log.Printf("reconciler shutdown failed: %v", err)
		}
	}()

	if stateFunc == nil {
		stateFunc = func() ReadinessState { return StateBooting }
	}

	retryDelay := max(time.Duration(cfg.RetryDelaySeconds)*time.Second, minRetryDelay)
	pollTimer := time.NewTimer(0)
	defer pollTimer.Stop()

	var lastPID int
	var lastBundle string
	lastState := ReadinessState("")
	var lastBundleErr string

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

		bundle, bundleErr := source.Bundle()
		if bundleErr != nil {
			if msg := bundleErr.Error(); msg != lastBundleErr {
				log.Printf("bundle read failed for %s: %v", source, bundleErr)
				lastBundleErr = msg
			}
			pollTimer.Reset(retryDelay)
			continue
		}
		lastBundleErr = ""

		if runErr := reconciler.Reconcile(pid, bundle); runErr != nil {
			log.Printf("reconcile failed for pid=%d %s: %v", pid, source, runErr)
			pollTimer.Reset(retryDelay)
			continue
		}

		if pid != lastPID {
			log.Printf("attached pid=%d %s", pid, source)
			lastPID = pid
			lastBundle = bundle
		} else if bundle != lastBundle {
			log.Printf("reloaded bundle for pid=%d %s", pid, source)
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
