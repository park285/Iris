package app

import (
	"errors"
	"flag"
	"fmt"
	"os"
	"path/filepath"
)

type Config struct {
	AgentName               string
	AgentOverride           string
	DeviceID                string
	FridaCoreDevkitDir      string
	ReadinessAddr           string
	HeartbeatTimeoutSeconds int
	PidPollIntervalSeconds  int
	RetryDelaySeconds       int
}

func ParseConfig(args []string) (Config, error) {
	fs := flag.NewFlagSet("graft-daemon", flag.ContinueOnError)
	agent := fs.String("agent", "", "agent bundle name")
	agentOverride := fs.String("agent-override", "", "override bundle file path")
	device := fs.String("device", "emulator-5554", "adb device id")
	devkit := fs.String("frida-core-devkit", "", "frida core devkit directory")
	readinessAddr := fs.String("readiness-addr", "127.0.0.1:17373", "local readiness endpoint bind address")
	heartbeatTimeout := fs.Int("heartbeat-timeout", 15, "agent heartbeat timeout seconds")
	pidPoll := fs.Int("pid-poll-interval", 30, "pid poll interval seconds")
	retryDelay := fs.Int("retry-delay", 5, "retry delay seconds")
	if err := fs.Parse(args); err != nil {
		return Config{}, fmt.Errorf("parse graft-daemon flags: %w", err)
	}

	if *agent == "" {
		return Config{}, errors.New("agent is required")
	}

	return Config{
		AgentName:               *agent,
		AgentOverride:           *agentOverride,
		DeviceID:                *device,
		FridaCoreDevkitDir:      *devkit,
		ReadinessAddr:           *readinessAddr,
		HeartbeatTimeoutSeconds: *heartbeatTimeout,
		PidPollIntervalSeconds:  *pidPoll,
		RetryDelaySeconds:       *retryDelay,
	}, nil
}

func ResolveRepoRoot(startDir string) (string, error) {
	current := filepath.Clean(startDir)

	for {
		if _, err := os.Stat(filepath.Join(current, "settings.gradle.kts")); err == nil {
			return current, nil
		}

		parent := filepath.Dir(current)
		if parent == current {
			return "", errors.New("repo root not found")
		}

		current = parent
	}
}
