package app

import (
	"os"
	"path/filepath"
	"strings"
	"testing"
)

func TestFileBundleSourceReadsFile(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.js")
	if err := os.WriteFile(path, []byte("console.log('hello');"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	source := NewFileBundleSource(path)
	bundle, err := source.Bundle()
	if err != nil {
		t.Fatalf("Bundle returned error: %v", err)
	}

	if bundle != "console.log('hello');" {
		t.Fatalf("Bundle = %q, want %q", bundle, "console.log('hello');")
	}
}

func TestFileBundleSourceReturnsErrorForMissingFile(t *testing.T) {
	source := NewFileBundleSource("/nonexistent/path.js")
	_, err := source.Bundle()
	if err == nil {
		t.Fatal("expected error for missing file")
	}
}

func TestFileBundleSourceString(t *testing.T) {
	source := NewFileBundleSource("/tmp/agent.js")
	got := source.String()
	if !strings.HasPrefix(got, "file:") {
		t.Fatalf("String() = %q, want prefix %q", got, "file:")
	}
}

func TestFileBundleSourceDetectsFileChanges(t *testing.T) {
	dir := t.TempDir()
	path := filepath.Join(dir, "agent.js")
	if err := os.WriteFile(path, []byte("v1"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	source := NewFileBundleSource(path)
	b1, _ := source.Bundle()

	if err := os.WriteFile(path, []byte("v2"), 0o600); err != nil {
		t.Fatalf("WriteFile returned error: %v", err)
	}

	b2, _ := source.Bundle()

	if b1 == b2 {
		t.Fatal("Bundle should return different content after file change")
	}
}
