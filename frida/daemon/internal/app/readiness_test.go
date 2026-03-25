package app

import (
	"context"
	"encoding/json"
	"io"
	"net/http"
	"net/http/httptest"
	"reflect"
	"testing"
	"time"
)

func TestReadinessTrackerStartsBootingAndMovesToHookingOnAttach(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 5 * time.Second,
		Now:          func() time.Time { return now },
	})

	booting := tracker.Snapshot()
	if booting.State != StateBooting {
		t.Fatalf("initial state = %q, want %q", booting.State, StateBooting)
	}
	if booting.Ready {
		t.Fatal("initial snapshot unexpectedly ready")
	}

	tracker.ObserveAttach(4321)
	hooking := tracker.Snapshot()
	if hooking.State != StateHooking {
		t.Fatalf("state after attach = %q, want %q", hooking.State, StateHooking)
	}
	if !hooking.Attached {
		t.Fatal("snapshot after attach should report attached=true")
	}
	if hooking.PID != 4321 {
		t.Fatalf("PID after attach = %d, want %d", hooking.PID, 4321)
	}
}

func TestReadinessTrackerBecomesReadyForFreshStructuredHealthMessage(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 10 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)

	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true,\"A\":true,\"u\":true},\"counters\":{\"injections\":3},\"timestampMs\":1711363200000,\"extra\":\"ignored\"}"}`)

	snapshot := tracker.Snapshot()
	if !snapshot.Ready {
		t.Fatal("snapshot should report ready=true")
	}
	if snapshot.State != StateReady {
		t.Fatalf("state = %q, want %q", snapshot.State, StateReady)
	}
	if snapshot.HeartbeatStale {
		t.Fatal("fresh heartbeat unexpectedly marked stale")
	}
	if got := snapshot.Hooks["u"]; !got {
		t.Fatalf("hook u = %v, want true", got)
	}
	if got := snapshot.Counters["injections"]; got != 3 {
		t.Fatalf("injections counter = %d, want %d", got, 3)
	}
	if got, ok := snapshot.LastPayload["extra"].(string); !ok || got != "ignored" {
		t.Fatalf("last payload extra = %#v, want %q", snapshot.LastPayload["extra"], "ignored")
	}
}

func TestReadinessTrackerFailsClosedWhenHeartbeatTurnsStale(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 3 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)
	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true,\"A\":true,\"u\":true},\"timestampMs\":1711363200000}"}`)

	now = now.Add(4 * time.Second)
	snapshot := tracker.Snapshot()
	if snapshot.Ready {
		t.Fatal("stale heartbeat should fail closed")
	}
	if snapshot.State != StateDegraded {
		t.Fatalf("state with stale heartbeat = %q, want %q", snapshot.State, StateDegraded)
	}
	if !snapshot.HeartbeatStale {
		t.Fatal("stale heartbeat should be reported")
	}
}

func TestReadinessTrackerBlocksWhenRequiredHookIsMissing(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 10 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)

	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true,\"A\":true},\"timestampMs\":1711363200000}"}`)

	snapshot := tracker.Snapshot()
	if snapshot.Ready {
		t.Fatal("missing required hooks should fail closed")
	}
	if snapshot.State != StateBlocked {
		t.Fatalf("state with missing required hook = %q, want %q", snapshot.State, StateBlocked)
	}
	if !reflect.DeepEqual(snapshot.MissingHooks, []string{"u"}) {
		t.Fatalf("MissingHooks = %v, want %v", snapshot.MissingHooks, []string{"u"})
	}
}

func TestReadinessTrackerIgnoresMalformedNonJSONMessages(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 10 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)

	tracker.ObserveScriptMessage("console log: hello from agent")
	snapshot := tracker.Snapshot()
	if snapshot.State != StateHooking {
		t.Fatalf("state after malformed message = %q, want %q", snapshot.State, StateHooking)
	}
	if snapshot.LastPayload != nil {
		t.Fatalf("LastPayload = %#v, want nil", snapshot.LastPayload)
	}
}

func TestReadinessHandlerReturnsJSONSnapshotAnd503WhenNotReady(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 10 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)
	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true},\"timestampMs\":1711363200000}"}`)

	recorder := httptest.NewRecorder()
	request := httptest.NewRequest(http.MethodGet, "/ready", nil)

	NewReadinessHandler(tracker).ServeHTTP(recorder, request)

	if recorder.Code != http.StatusServiceUnavailable {
		t.Fatalf("status code = %d, want %d", recorder.Code, http.StatusServiceUnavailable)
	}

	var snapshot ReadinessSnapshot
	if err := json.NewDecoder(recorder.Body).Decode(&snapshot); err != nil {
		t.Fatalf("decode response: %v", err)
	}
	if snapshot.Ready {
		t.Fatal("HTTP snapshot should fail closed for missing hooks")
	}
	if snapshot.State != StateBlocked {
		t.Fatalf("HTTP snapshot state = %q, want %q", snapshot.State, StateBlocked)
	}
	if snapshot.Detail == "" {
		t.Fatal("HTTP snapshot detail should be populated")
	}
	if snapshot.TimestampMs != now.UnixMilli() {
		t.Fatalf("HTTP snapshot timestampMs = %d, want %d", snapshot.TimestampMs, now.UnixMilli())
	}
}

func TestStartReadinessServerServesStableReadyRoute(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 10 * time.Second,
		Now:          func() time.Time { return now },
	})
	tracker.ObserveAttach(4321)
	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true,\"A\":true,\"u\":true},\"timestampMs\":1711363200000}"}`)

	ctx, cancel := context.WithCancel(context.Background())
	defer cancel()

	server, err := StartReadinessServer(ctx, "127.0.0.1:17373", tracker)
	if err != nil {
		t.Fatalf("StartReadinessServer returned error: %v", err)
	}
	defer server.Close()

	response, err := http.Get("http://127.0.0.1:17373/ready")
	if err != nil {
		t.Fatalf("GET /ready returned error: %v", err)
	}
	defer response.Body.Close()

	if response.StatusCode != http.StatusOK {
		t.Fatalf("status code = %d, want %d", response.StatusCode, http.StatusOK)
	}

	body, err := io.ReadAll(response.Body)
	if err != nil {
		t.Fatalf("ReadAll returned error: %v", err)
	}

	var snapshot ReadinessSnapshot
	if err := json.Unmarshal(body, &snapshot); err != nil {
		t.Fatalf("Unmarshal returned error: %v", err)
	}
	if !snapshot.Ready {
		t.Fatal("stable /ready endpoint should report ready=true for healthy state")
	}
}
