package fridaapi

import "testing"

func TestParseHealthMessageAcceptsNestedHookAndCounterPayloads(t *testing.T) {
	message := `{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":{\"installed\":true},\"u\":{\"installed\":false}},\"counters\":{\"capture\":{\"success\":2,\"skip\":1},\"inject\":{\"failure\":3}},\"timestampMs\":1711363200000}"}`

	health, ok := ParseHealthMessage(message)
	if !ok {
		t.Fatal("ParseHealthMessage returned ok=false")
	}
	if !health.Hooks["b6"] {
		t.Fatal("expected b6 hook to parse as installed=true")
	}
	if health.Hooks["u"] {
		t.Fatal("expected u hook to parse as installed=false")
	}
	if got := health.Counters["capture.success"]; got != 2 {
		t.Fatalf("capture.success = %d, want %d", got, 2)
	}
	if got := health.Counters["capture.skip"]; got != 1 {
		t.Fatalf("capture.skip = %d, want %d", got, 1)
	}
	if got := health.Counters["inject.failure"]; got != 3 {
		t.Fatalf("inject.failure = %d, want %d", got, 3)
	}
}
