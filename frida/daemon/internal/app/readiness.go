package app

import (
	"context"
	"errors"
	"fmt"
	"log"
	"maps"
	"net"
	"net/http"
	"sort"
	"strings"
	"sync"
	"time"

	json "github.com/goccy/go-json"

	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
)

type ReadinessState string

const (
	StateBooting         ReadinessState = "BOOTING"
	StateHooking         ReadinessState = "HOOKING"
	StateWarm            ReadinessState = "WARM"
	StateReady           ReadinessState = "READY"
	StateDegraded        ReadinessState = "DEGRADED"
	StateBlocked         ReadinessState = "BLOCKED"
	DefaultReadinessPath                = "/ready"
)

var defaultRequiredHooks = []string{"A", "b6", "o", "t", "u"}

type ReadinessOptions struct {
	RequiredHooks []string
	HeartbeatTTL  time.Duration
	Now           func() time.Time
}

type ReadinessSnapshot struct {
	Ready            bool             `json:"ready"`
	State            ReadinessState   `json:"state"`
	Detail           string           `json:"detail"`
	TimestampMs      int64            `json:"timestampMs"`
	Attached         bool             `json:"attached"`
	PID              int              `json:"pid,omitempty"`
	LastEvent        string           `json:"lastEvent,omitempty"`
	LastHeartbeatMs  int64            `json:"lastHeartbeatMs,omitempty"`
	HeartbeatAgeMs   int64            `json:"heartbeatAgeMs,omitempty"`
	HeartbeatStale   bool             `json:"heartbeatStale"`
	MissingHooks     []string         `json:"missingHooks,omitempty"`
	Hooks            map[string]bool  `json:"hooks,omitempty"`
	Counters         map[string]int64 `json:"counters,omitempty"`
	LastPayload      map[string]any   `json:"lastPayload,omitempty"`
	AgentTimestampMs int64            `json:"agentTimestampMs,omitempty"`
}

type ReadinessTracker struct {
	mu            sync.RWMutex
	requiredHooks []string
	heartbeatTTL  time.Duration
	now           func() time.Time
	attached      bool
	pid           int
	baseState     ReadinessState
	lastHeartbeat time.Time
	lastHealth    *readinessHealth
}

type readinessHealth struct {
	event       string
	ready       bool
	hooks       map[string]bool
	counters    map[string]int64
	timestampMs int64
	payload     map[string]any
}

func NewReadinessTracker(opts ReadinessOptions) *ReadinessTracker {
	requiredHooks := append([]string(nil), opts.RequiredHooks...)
	if len(requiredHooks) == 0 {
		requiredHooks = append([]string(nil), defaultRequiredHooks...)
	}

	sort.Strings(requiredHooks)

	now := opts.Now
	if now == nil {
		now = time.Now
	}

	heartbeatTTL := opts.HeartbeatTTL
	if heartbeatTTL <= 0 {
		heartbeatTTL = 15 * time.Second
	}

	return &ReadinessTracker{
		requiredHooks: requiredHooks,
		heartbeatTTL:  heartbeatTTL,
		now:           now,
		baseState:     StateBooting,
	}
}

func (t *ReadinessTracker) ObserveAttach(pid int) {
	t.mu.Lock()
	defer t.mu.Unlock()

	t.attached = true
	t.pid = pid
	t.baseState = StateHooking
	t.lastHeartbeat = time.Time{}
	t.lastHealth = nil
}

func (t *ReadinessTracker) Attached(pid int) {
	t.ObserveAttach(pid)
}

func (t *ReadinessTracker) ObserveDetach() {
	t.mu.Lock()
	defer t.mu.Unlock()

	t.attached = false
	t.pid = 0
	t.baseState = StateBooting
	t.lastHeartbeat = time.Time{}
	t.lastHealth = nil
}

func (t *ReadinessTracker) Detached() {
	t.ObserveDetach()
}

func (t *ReadinessTracker) ObserveScriptMessage(message string) bool {
	health, ok := fridaapi.ParseHealthMessage(message)
	if !ok {
		return false
	}

	t.mu.Lock()
	defer t.mu.Unlock()

	t.lastHeartbeat = t.now()
	t.lastHealth = &readinessHealth{
		event:       health.Event,
		ready:       health.Ready,
		hooks:       copyBoolMap(health.Hooks),
		counters:    copyInt64Map(health.Counters),
		timestampMs: health.TimestampMs,
		payload:     copyAnyMap(health.Payload),
	}

	t.baseState = normalizeState(health.State)
	if t.baseState == "" {
		if health.Ready {
			t.baseState = StateReady
		} else {
			t.baseState = StateWarm
		}
	}

	return true
}

func (t *ReadinessTracker) CurrentState() ReadinessState {
	return t.Snapshot().State
}

func (t *ReadinessTracker) Snapshot() ReadinessSnapshot {
	return cloneReadinessSnapshot(buildReadinessSnapshot(t.snapshotInputs()))
}

type readinessSnapshotInputs struct {
	attached      bool
	pid           int
	baseState     ReadinessState
	lastHeartbeat time.Time
	lastHealth    *readinessHealth
	requiredHooks []string
	now           func() time.Time
	heartbeatTTL  time.Duration
}

func (t *ReadinessTracker) snapshotInputs() readinessSnapshotInputs {
	t.mu.RLock()

	inputs := readinessSnapshotInputs{
		attached:      t.attached,
		pid:           t.pid,
		baseState:     t.baseState,
		lastHeartbeat: t.lastHeartbeat,
		requiredHooks: append([]string(nil), t.requiredHooks...),
		now:           t.now,
		heartbeatTTL:  t.heartbeatTTL,
	}

	inputs.lastHealth = t.lastHealth

	t.mu.RUnlock()

	return inputs
}

func buildReadinessSnapshot(inputs readinessSnapshotInputs) ReadinessSnapshot {
	snapshot := ReadinessSnapshot{
		State:       inputs.baseState,
		TimestampMs: inputs.now().UnixMilli(),
		Attached:    inputs.attached,
		PID:         inputs.pid,
	}

	if !inputs.attached {
		snapshot.State = StateBooting
		snapshot.Detail = "waiting for Frida attach"

		return snapshot
	}

	if inputs.lastHealth == nil {
		snapshot.State = StateHooking
		snapshot.Detail = fmt.Sprintf("attached to pid %d; waiting for agent health", inputs.pid)

		return snapshot
	}

	populateSnapshotHealth(&snapshot, inputs)

	missingHooks := populateSnapshotDerivedState(&snapshot, inputs)

	if isHeartbeatStale(inputs, snapshot.HeartbeatAgeMs) {
		snapshot.State = StateDegraded
		snapshot.HeartbeatStale = true
		snapshot.Detail = fmt.Sprintf("agent heartbeat stale for %dms", snapshot.HeartbeatAgeMs)

		return snapshot
	}

	if len(missingHooks) > 0 {
		setMissingHooksDetail(&snapshot, inputs, missingHooks)
		return snapshot
	}

	return applyBaseStateDetail(snapshot, inputs)
}

func applyBaseStateDetail(snapshot ReadinessSnapshot, inputs readinessSnapshotInputs) ReadinessSnapshot {
	switch inputs.baseState {
	case StateReady:
		snapshot.Ready = inputs.lastHealth.ready
		if snapshot.Ready {
			snapshot.Detail = "agent heartbeat is fresh and all required hooks are installed"
			return snapshot
		}

		snapshot.State = StateWarm
		snapshot.Detail = "agent reported READY state without ready=true"

		return snapshot
	case StateBlocked:
		snapshot.Detail = "agent reported blocked state"
		return snapshot
	case StateDegraded:
		snapshot.Detail = "agent reported degraded state"
		return snapshot
	case StateWarm:
		snapshot.Detail = "agent warmed but not yet ready"
		return snapshot
	case StateHooking:
		snapshot.Detail = "agent is still installing required hooks"
		return snapshot
	default:
		if inputs.lastHealth.ready {
			snapshot.State = StateReady
			snapshot.Ready = true
			snapshot.Detail = "agent heartbeat is fresh and all required hooks are installed"

			return snapshot
		}

		snapshot.State = StateWarm
		snapshot.Detail = "agent health received but readiness is still false"

		return snapshot
	}
}

func populateSnapshotHealth(snapshot *ReadinessSnapshot, inputs readinessSnapshotInputs) {
	snapshot.LastEvent = inputs.lastHealth.event
	snapshot.LastHeartbeatMs = unixMilli(inputs.lastHeartbeat)
	snapshot.AgentTimestampMs = inputs.lastHealth.timestampMs
	snapshot.Hooks = inputs.lastHealth.hooks
	snapshot.Counters = inputs.lastHealth.counters
	snapshot.LastPayload = inputs.lastHealth.payload

	if !inputs.lastHeartbeat.IsZero() {
		snapshot.HeartbeatAgeMs = inputs.now().UnixMilli() - inputs.lastHeartbeat.UnixMilli()
	}
}

func populateSnapshotDerivedState(snapshot *ReadinessSnapshot, inputs readinessSnapshotInputs) []string {
	missingHooks := missingRequiredHooks(inputs.requiredHooks, inputs.lastHealth.hooks)
	if len(missingHooks) > 0 {
		snapshot.MissingHooks = missingHooks
	}

	return missingHooks
}

func isHeartbeatStale(inputs readinessSnapshotInputs, heartbeatAgeMs int64) bool {
	return inputs.heartbeatTTL > 0 &&
		!inputs.lastHeartbeat.IsZero() &&
		inputs.now().Sub(inputs.lastHeartbeat) > inputs.heartbeatTTL &&
		heartbeatAgeMs >= 0
}

func setMissingHooksDetail(snapshot *ReadinessSnapshot, inputs readinessSnapshotInputs, missingHooks []string) {
	if inputs.lastHealth.ready || inputs.baseState == StateReady || inputs.baseState == StateBlocked {
		snapshot.State = StateBlocked
		snapshot.Detail = fmt.Sprintf("missing required hooks: %s", strings.Join(missingHooks, ", "))

		return
	}

	snapshot.State = StateHooking
	snapshot.Detail = fmt.Sprintf("waiting for required hooks: %s", strings.Join(missingHooks, ", "))
}

type snapshotter interface {
	Snapshot() ReadinessSnapshot
}

func NewReadinessHandler(source snapshotter) http.Handler {
	return http.HandlerFunc(func(w http.ResponseWriter, r *http.Request) {
		if r.Method != http.MethodGet {
			http.Error(w, "method not allowed", http.StatusMethodNotAllowed)
			return
		}

		snapshot := source.Snapshot()

		status := http.StatusOK

		if !snapshot.Ready {
			status = http.StatusServiceUnavailable
		}

		w.Header().Set("Content-Type", "application/json")
		w.WriteHeader(status)

		_ = json.NewEncoder(w).Encode(snapshot)
	})
}

func StartReadinessServer(ctx context.Context, addr string, source snapshotter) (*http.Server, error) {
	listenConfig := net.ListenConfig{}

	listener, err := listenConfig.Listen(ctx, "tcp", addr)
	if err != nil {
		return nil, fmt.Errorf("listen on %s: %w", addr, err)
	}

	mux := http.NewServeMux()
	mux.Handle(DefaultReadinessPath, NewReadinessHandler(source))

	server := &http.Server{
		Handler:           mux,
		ReadHeaderTimeout: 5 * time.Second,
	}

	go func() {
		<-ctx.Done()

		shutdownCtx, cancel := context.WithTimeout(context.WithoutCancel(ctx), 5*time.Second)
		defer cancel()

		_ = server.Shutdown(shutdownCtx)
	}()

	go func() {
		if err := server.Serve(listener); err != nil && !errors.Is(err, http.ErrServerClosed) {
			log.Printf("readiness server stopped unexpectedly: %v", err)
		}
	}()

	return server, nil
}

func normalizeState(state string) ReadinessState {
	switch strings.ToUpper(state) {
	case string(StateBooting):
		return StateBooting
	case string(StateHooking):
		return StateHooking
	case string(StateWarm):
		return StateWarm
	case string(StateReady):
		return StateReady
	case string(StateDegraded):
		return StateDegraded
	case string(StateBlocked):
		return StateBlocked
	default:
		return ""
	}
}

func missingRequiredHooks(required []string, hooks map[string]bool) []string {
	missing := make([]string, 0, len(required))
	for _, hook := range required {
		if !hooks[hook] {
			missing = append(missing, hook)
		}
	}

	return missing
}

func unixMilli(ts time.Time) int64 {
	if ts.IsZero() {
		return 0
	}

	return ts.UnixMilli()
}

func copyBoolMap(src map[string]bool) map[string]bool {
	if len(src) == 0 {
		return nil
	}

	dst := make(map[string]bool, len(src))
	maps.Copy(dst, src)

	return dst
}

func copyInt64Map(src map[string]int64) map[string]int64 {
	if len(src) == 0 {
		return nil
	}

	dst := make(map[string]int64, len(src))
	maps.Copy(dst, src)

	return dst
}

func copyAnyMap(src map[string]any) map[string]any {
	if len(src) == 0 {
		return nil
	}

	dst := make(map[string]any, len(src))
	maps.Copy(dst, src)

	return dst
}

func cloneReadinessSnapshot(snapshot ReadinessSnapshot) ReadinessSnapshot {
	snapshot.Hooks = copyBoolMap(snapshot.Hooks)
	snapshot.Counters = copyInt64Map(snapshot.Counters)
	snapshot.LastPayload = copyAnyMap(snapshot.LastPayload)
	snapshot.MissingHooks = append([]string(nil), snapshot.MissingHooks...)

	return snapshot
}
