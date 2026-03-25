package agentbuild

import (
	"os"
	"path/filepath"
	"testing"
)

type fakeBundler struct {
	called      bool
	projectRoot string
	entryPoint  string
	result      string
	err         error
}

func (f *fakeBundler) Build(projectRoot, entryPoint string) (string, error) {
	f.called = true
	f.projectRoot = projectRoot
	f.entryPoint = entryPoint
	return f.result, f.err
}

func TestCompileCallsBundlerWithPinnedPaths(t *testing.T) {
	tmp := t.TempDir()
	req := Request{
		ProjectRoot: filepath.Join(tmp, "frida", "agent"),
		EntryPoint:  filepath.Join(tmp, "frida", "agent", "thread-image-graft.ts"),
	}
	bundler := &fakeBundler{result: "bundle"}

	got, err := Compile(req, bundler)
	if err != nil {
		t.Fatalf("Compile returned error: %v", err)
	}
	if !bundler.called {
		t.Fatal("expected bundler to be called")
	}
	if bundler.projectRoot != req.ProjectRoot || bundler.entryPoint != req.EntryPoint {
		t.Fatalf("bundler called with (%q, %q), want (%q, %q)", bundler.projectRoot, bundler.entryPoint, req.ProjectRoot, req.EntryPoint)
	}
	if got != "bundle" {
		t.Fatalf("Compile = %q, want %q", got, "bundle")
	}
}

func TestCompileRejectsEntryPointOutsideProjectRoot(t *testing.T) {
	tmp := t.TempDir()
	req := Request{
		ProjectRoot: filepath.Join(tmp, "frida", "agent"),
		EntryPoint:  filepath.Join(tmp, "frida", "other", "evil.ts"),
	}
	if _, err := Compile(req, &fakeBundler{}); err == nil {
		t.Fatal("expected error for entry point outside project root")
	}
}

func TestCompileWritesDumpBundle(t *testing.T) {
	tmp := t.TempDir()
	req := Request{
		ProjectRoot: filepath.Join(tmp, "frida", "agent"),
		EntryPoint:  filepath.Join(tmp, "frida", "agent", "thread-image-graft.ts"),
		DumpPath:    filepath.Join(tmp, "generated", "thread-image-graft.js"),
	}
	bundler := &fakeBundler{result: "bundle-content"}

	got, err := Compile(req, bundler)
	if err != nil {
		t.Fatalf("Compile returned error: %v", err)
	}
	if got != "bundle-content" {
		t.Fatalf("Compile = %q, want %q", got, "bundle-content")
	}
	data, err := os.ReadFile(req.DumpPath)
	if err != nil {
		t.Fatalf("ReadFile returned error: %v", err)
	}
	if string(data) != "bundle-content" {
		t.Fatalf("dump file = %q, want %q", string(data), "bundle-content")
	}
}
