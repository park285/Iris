package app

import (
	"os"
	"testing"
)

func TestParseConfigUsesPinnedAgentProjectRoot(t *testing.T) {
	cfg, err := ParseConfig("/repo", []string{"--agent", "thread-image-graft.ts"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}

	if got, want := cfg.AgentProjectRoot, "/repo/frida/agent"; got != want {
		t.Fatalf("AgentProjectRoot = %q, want %q", got, want)
	}
	if got, want := cfg.AgentEntryPoint, "/repo/frida/agent/thread-image-graft.ts"; got != want {
		t.Fatalf("AgentEntryPoint = %q, want %q", got, want)
	}
	if got, want := cfg.DeviceID, "emulator-5554"; got != want {
		t.Fatalf("DeviceID = %q, want %q", got, want)
	}
	if got, want := cfg.PidPollIntervalSeconds, 30; got != want {
		t.Fatalf("PidPollIntervalSeconds = %d, want %d", got, want)
	}
	if got, want := cfg.RetryDelaySeconds, 5; got != want {
		t.Fatalf("RetryDelaySeconds = %d, want %d", got, want)
	}
}

func TestParseConfigRejectsEscapingAgentPath(t *testing.T) {
	_, err := ParseConfig("/repo", []string{"--agent", "../evil.ts"})
	if err == nil {
		t.Fatal("expected error for escaping agent path")
	}
}

func TestParseConfigCarriesDevkitPathWhenProvided(t *testing.T) {
	cfg, err := ParseConfig("/repo", []string{"--agent", "thread-image-graft.ts", "--frida-core-devkit", "/opt/frida/devkit"})
	if err != nil {
		t.Fatalf("ParseConfig returned error: %v", err)
	}

	if got, want := cfg.FridaCoreDevkitDir, "/opt/frida/devkit"; got != want {
		t.Fatalf("FridaCoreDevkitDir = %q, want %q", got, want)
	}
}

func TestResolveRepoRootWalksUpFromDaemonDir(t *testing.T) {
	root := t.TempDir()
	deep := root + "/frida/daemon"
	if err := os.MkdirAll(deep, 0o755); err != nil {
		t.Fatalf("MkdirAll returned error: %v", err)
	}
	if err := os.WriteFile(root+"/settings.gradle.kts", []byte("rootProject.name = \"Iris\""), 0o644); err != nil {
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
