package app

import (
	"os"
	"testing"
)

func TestParseConfigSetsAgentName(t *testing.T) {
	cfg, err := ParseConfig([]string{"--agent", "thread-image-graft"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}
	if got, want := cfg.AgentName, "thread-image-graft"; got != want {
		t.Fatalf("AgentName = %q, want %q", got, want)
	}
	if cfg.AgentOverride != "" {
		t.Fatalf("AgentOverride = %q, want empty", cfg.AgentOverride)
	}
}

func TestParseConfigRejectsEmptyAgent(t *testing.T) {
	_, err := ParseConfig([]string{})
	if err == nil {
		t.Fatal("expected error for missing --agent")
	}
}

func TestParseConfigSetsAgentOverride(t *testing.T) {
	cfg, err := ParseConfig([]string{"--agent", "thread-image-graft", "--agent-override", "/tmp/custom.js"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}
	if got, want := cfg.AgentOverride, "/tmp/custom.js"; got != want {
		t.Fatalf("AgentOverride = %q, want %q", got, want)
	}
}

func TestParseConfigDefaults(t *testing.T) {
	cfg, err := ParseConfig([]string{"--agent", "thread-image-graft"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}
	if got, want := cfg.DeviceID, "emulator-5554"; got != want {
		t.Fatalf("DeviceID = %q, want %q", got, want)
	}
	if got, want := cfg.ReadinessAddr, "127.0.0.1:17373"; got != want {
		t.Fatalf("ReadinessAddr = %q, want %q", got, want)
	}
	if got, want := cfg.HeartbeatTimeoutSeconds, 15; got != want {
		t.Fatalf("HeartbeatTimeoutSeconds = %d, want %d", got, want)
	}
	if got, want := cfg.PidPollIntervalSeconds, 30; got != want {
		t.Fatalf("PidPollIntervalSeconds = %d, want %d", got, want)
	}
	if got, want := cfg.RetryDelaySeconds, 5; got != want {
		t.Fatalf("RetryDelaySeconds = %d, want %d", got, want)
	}
}

func TestParseConfigCarriesDevkitPath(t *testing.T) {
	cfg, err := ParseConfig([]string{"--agent", "thread-image-graft", "--frida-core-devkit", "/opt/devkit"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}
	if got, want := cfg.FridaCoreDevkitDir, "/opt/devkit"; got != want {
		t.Fatalf("FridaCoreDevkitDir = %q, want %q", got, want)
	}
}

func TestParseConfigCarriesReadinessSettings(t *testing.T) {
	cfg, err := ParseConfig([]string{
		"--agent", "thread-image-graft",
		"--readiness-addr", "127.0.0.1:18999",
		"--heartbeat-timeout", "17",
	})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}
	if got, want := cfg.ReadinessAddr, "127.0.0.1:18999"; got != want {
		t.Fatalf("ReadinessAddr = %q, want %q", got, want)
	}
	if got, want := cfg.HeartbeatTimeoutSeconds, 17; got != want {
		t.Fatalf("HeartbeatTimeoutSeconds = %d, want %d", got, want)
	}
}

func TestResolveRepoRootWalksUpFromDaemonDir(t *testing.T) {
	root := t.TempDir()

	deep := root + "/frida/daemon"
	if err := os.MkdirAll(deep, 0o750); err != nil {
		t.Fatalf("MkdirAll returned error: %v", err)
	}

	if err := os.WriteFile(root+"/settings.gradle.kts", []byte("rootProject.name = \"Iris\""), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	got, err := ResolveRepoRoot(deep)
	if err != nil {
		t.Fatalf("ResolveRepoRoot returned error: %v", err)
	}

	if got != root {
		t.Fatalf("ResolveRepoRoot = %q, want %q", got, root)
	}
}
