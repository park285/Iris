package app

import (
	"errors"
	"flag"
	"os"
	"path/filepath"
	"strings"
)

type Config struct {
	AgentProjectRoot       string
	AgentEntryPoint        string
	DeviceID               string
	FridaCoreDevkitDir     string
	PidPollIntervalSeconds int
	RetryDelaySeconds      int
}

func ParseConfig(repoRoot string, args []string) (Config, error) {
	fs := flag.NewFlagSet("graft-daemon", flag.ContinueOnError)
	agent := fs.String("agent", "", "agent entry point relative to frida/agent")
	device := fs.String("device", "emulator-5554", "adb device id")
	devkit := fs.String("frida-core-devkit", "", "frida core devkit directory")
	pidPoll := fs.Int("pid-poll-interval", 30, "pid poll interval seconds")
	retryDelay := fs.Int("retry-delay", 5, "retry delay seconds")
	if err := fs.Parse(args); err != nil {
		return Config{}, err
	}
	if *agent == "" {
		return Config{}, errors.New("agent is required")
	}

	projectRoot := filepath.Join(repoRoot, "frida", "agent")
	cleanAgent := filepath.Clean(*agent)
	if strings.HasPrefix(cleanAgent, "..") || filepath.IsAbs(cleanAgent) {
		return Config{}, errors.New("agent must stay within frida/agent")
	}

	return Config{
		AgentProjectRoot:       projectRoot,
		AgentEntryPoint:        filepath.Join(projectRoot, cleanAgent),
		DeviceID:               *device,
		FridaCoreDevkitDir:     *devkit,
		PidPollIntervalSeconds: *pidPoll,
		RetryDelaySeconds:      *retryDelay,
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
