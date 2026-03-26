package agentbundle

import (
	"strings"
	"testing"
)

func TestSourceReturnsEmbeddedBundle(t *testing.T) {
	source, err := Source("thread-image-graft")
	if err != nil {
		t.Fatalf("Source returned error: %v", err)
	}

	bundle, err := source.Bundle()
	if err != nil {
		t.Fatalf("Bundle returned error: %v", err)
	}

	if len(bundle) == 0 {
		t.Fatal("Bundle returned empty string")
	}
}

func TestSourceReturnsErrorForUnknownBundle(t *testing.T) {
	_, err := Source("nonexistent-agent")
	if err == nil {
		t.Fatal("expected error for unknown bundle")
	}

	if !strings.Contains(err.Error(), "available") {
		t.Fatalf("error should contain available bundles: %v", err)
	}
}

func TestAvailableReturnsSortedBundleNames(t *testing.T) {
	names := Available()
	if len(names) < 2 {
		t.Fatalf("Available() = %v, want at least 2 entries", names)
	}

	for i := 1; i < len(names); i++ {
		if names[i] < names[i-1] {
			t.Fatalf("Available() not sorted: %v", names)
		}
	}
}

func TestStaticSourceString(t *testing.T) {
	source, err := Source("thread-image-graft")
	if err != nil {
		t.Fatalf("Source returned error: %v", err)
	}

	got := source.String()
	want := "embedded:thread-image-graft"
	if got != want {
		t.Fatalf("String() = %q, want %q", got, want)
	}
}
