# Go Daemon Optimization Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Go 데몬의 P1(same-PID bundle reload), P2(adaptive polling), P3(build error caching), P4(state-transition logging), P6(startup capability check) 최적화를 구현한다.

**Architecture:** Runner에 bundle digest 추적을 추가하여 같은 PID에서도 source 변경 시 reload 가능하게 하고, run 루프를 timer 기반으로 전환하여 ReadinessTracker 상태에 따라 poll 주기를 동적 조정한다. bundleCache에 에러 캐싱을 추가하여 source 미변경 시 재빌드를 차단하고, 매 poll 로그를 상태 전이 로그로 교체한다. Runtime 인터페이스에 Available()을 추가하여 startup 시 빠른 실패를 보장한다.

**Tech Stack:** Go 1.24, frida-go, fsnotify, hash/fnv

**Design Decisions:**
- **Bundle 비교: digest 기반** — `Compile()`이 JS 소스 전체 문자열을 반환하므로, Runner에 대형 문자열 두 벌 저장 대신 FNV-1a digest(`uint64`)로 비교. 메모리 절감 + O(1) 비교.
- **Build 실패 정책: fail-closed** — source 변경 후 빌드 실패 시 이전 bundle 미제공. Frida agent는 KakaoTalk obfuscated field 매핑에 종속되어 stale bundle 제공 시 silent corruption 위험. `bundleCache`는 에러 발생 시 `ready=false`로 설정하여 이전 bundle 접근 차단.
- **실패 시 retry 전략: fast retry** — `nextPollInterval(state)`은 성공 후 happy path 전용. PID/build/reconcile 실패는 모두 `retryDelay` 사용. build error는 P3 캐싱으로 재빌드 차단되므로 fast retry 비용 최소.
- **Startup check: fast fail** — `frida_core` 빌드 태그 누락 시 복구 경로 없음. BLOCKED 상태 노출 대신 process 즉시 종료가 정확. 운영자에게 명확한 에러 메시지 제공.

---

## File Structure

### Modified Files

| 파일 | 변경 내용 |
|------|----------|
| `frida/daemon/internal/fridaapi/runtime.go` | `Available() bool` 인터페이스 추가 |
| `frida/daemon/internal/fridaapi/client.go` | `unavailableRuntime.Available()` 구현 |
| `frida/daemon/internal/fridaapi/client_real.go` | `realRuntime.Available()` 구현 |
| `frida/daemon/internal/lifecycle/runner.go` | `bundleDigest uint64` 필드 + FNV-1a digest 비교 분기 |
| `frida/daemon/internal/lifecycle/runner_test.go` | bundle reload 테스트 + `fakeRuntime.Available()` |
| `frida/daemon/internal/app/run.go` | bundleCache 에러 캐싱, timer 기반 루프, stateFunc, 상태 전이 로그 |
| `frida/daemon/internal/app/run_test.go` | build error cache 테스트, adaptive poll 테스트, stateFunc 파라미터 |
| `frida/daemon/internal/app/readiness.go` | `CurrentState()` 경량 메서드 |
| `frida/daemon/internal/app/readiness_test.go` | `CurrentState()` 테스트 |
| `frida/daemon/cmd/graft-daemon/main.go` | runtime 추출, Available() 검사, stateFunc 전달 |

---

### Task 1: P6 — Runtime.Available() + startup check

**설계 의도:** `frida_core` 빌드 태그 누락 시 복구 경로가 없으므로, BLOCKED 상태 노출 대신 process를 즉시 종료하여 운영자에게 명확한 에러를 전달한다.

**Files:**
- Modify: `frida/daemon/internal/fridaapi/runtime.go:9-12`
- Modify: `frida/daemon/internal/fridaapi/client.go`
- Modify: `frida/daemon/internal/fridaapi/client_real.go`
- Modify: `frida/daemon/internal/lifecycle/runner_test.go:8-12`
- Modify: `frida/daemon/cmd/graft-daemon/main.go:53-55`

- [ ] **Step 1: Runtime 인터페이스에 Available() 추가**

`frida/daemon/internal/fridaapi/runtime.go`:
```go
type Runtime interface {
	Attach(pid int, bundle string) error
	UnloadAndDetach() error
	Available() bool
}
```

- [ ] **Step 2: 양쪽 빌드 태그에 구현 추가**

`frida/daemon/internal/fridaapi/client.go` (끝에 추가):
```go
func (unavailableRuntime) Available() bool {
	return false
}
```

`frida/daemon/internal/fridaapi/client_real.go` (끝에 추가):
```go
func (*realRuntime) Available() bool {
	return true
}
```

- [ ] **Step 3: 테스트 fakeRuntime에 Available() 추가**

`frida/daemon/internal/lifecycle/runner_test.go` fakeRuntime에 추가:
```go
func (*fakeRuntime) Available() bool {
	return true
}
```

- [ ] **Step 4: main.go에 startup check 추가**

`frida/daemon/cmd/graft-daemon/main.go` 기존:
```go
reconciler := lifecycle.NewRunner(fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir, func(message string) {
    readiness.ObserveScriptMessage(message)
}), readiness)
```

변경:
```go
runtime := fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir, func(message string) {
    readiness.ObserveScriptMessage(message)
})

if !runtime.Available() {
    return fmt.Errorf("frida runtime backend is not available; rebuild with -tags frida_core")
}

reconciler := lifecycle.NewRunner(runtime, readiness)
```

- [ ] **Step 5: 컴파일 검증**

Run: `cd frida/daemon && GOWORK=off go build ./...`
Expected: 성공 (인터페이스 구현 일치)

- [ ] **Step 6: 테스트 실행**

Run: `cd frida/daemon && GOWORK=off go test ./internal/lifecycle/ -v`
Expected: 기존 테스트 전부 PASS

- [ ] **Step 7: Commit**

```bash
git add frida/daemon/internal/fridaapi/runtime.go frida/daemon/internal/fridaapi/client.go frida/daemon/internal/fridaapi/client_real.go frida/daemon/internal/lifecycle/runner_test.go frida/daemon/cmd/graft-daemon/main.go
git commit -m "Runtime.Available() 추가 및 startup capability check 도입"
```

---

### Task 2: P1 — same-PID bundle reload (digest 기반)

**설계 의도:** `Compile()`이 JS 소스 전체 문자열을 반환하므로, Runner에 대형 문자열 두 벌 저장 대신 FNV-1a digest(`uint64`)로 비교한다. `hash/fnv`는 stdlib이며 non-crypto용으로 충분.

**Files:**
- Modify: `frida/daemon/internal/lifecycle/runner.go`
- Modify: `frida/daemon/internal/lifecycle/runner_test.go`

- [ ] **Step 1: bundle reload 테스트 작성**

`frida/daemon/internal/lifecycle/runner_test.go`에 추가:
```go
func TestRunnerReloadsWhenBundleChangesForSamePID(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}

	if err := r.Reconcile(1234, "bundle-v2"); err != nil {
		t.Fatalf("second Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load", "unload", "detach", "attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}

	if !reflect.DeepEqual(observer.events, []string{"attached", "detached", "attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached", "detached", "attached"})
	}
}

func TestRunnerSkipsReconcileForSamePIDAndBundle(t *testing.T) {
	rt := &fakeRuntime{}
	observer := &fakeObserver{}
	r := NewRunner(rt, observer)

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("first Reconcile returned error: %v", err)
	}

	if err := r.Reconcile(1234, "bundle-v1"); err != nil {
		t.Fatalf("second Reconcile returned error: %v", err)
	}

	want := []string{"attach", "create-script", "load"}
	if !reflect.DeepEqual(rt.events, want) {
		t.Fatalf("events = %v, want %v", rt.events, want)
	}

	if !reflect.DeepEqual(observer.events, []string{"attached"}) {
		t.Fatalf("observer events = %v, want %v", observer.events, []string{"attached"})
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/lifecycle/ -run "TestRunnerReloadsWhenBundleChanges|TestRunnerSkipsReconcile" -v`
Expected: `TestRunnerReloadsWhenBundleChangesForSamePID` FAIL (현재 `pid == r.currentPID`이면 bundle 무관 no-op이므로 unload/detach 이벤트 없음), `TestRunnerSkipsReconcileForSamePIDAndBundle` PASS (현재 `pid == r.currentPID`이면 무조건 no-op이므로 same-bundle 기대값과 일치)

- [ ] **Step 3: Runner 구현 변경**

`frida/daemon/internal/lifecycle/runner.go` 전체 교체:
```go
package lifecycle

import (
	"fmt"
	"hash/fnv"

	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
)

// bundleDigest는 bundle 문자열(JS 소스 전체)의 FNV-1a hash.
// 대형 문자열 두 벌 저장 대신 uint64 비교로 변경 감지.
func bundleDigest(bundle string) uint64 {
	h := fnv.New64a()
	_, _ = h.Write([]byte(bundle))
	return h.Sum64()
}

type Runner struct {
	runtime      fridaapi.Runtime
	observer     Observer
	currentPID   int
	currentDigest uint64
	attached     bool
}

type Observer interface {
	Attached(pid int)
	Detached()
}

func NewRunner(runtime fridaapi.Runtime, observer Observer) *Runner {
	return &Runner{runtime: runtime, observer: observer}
}

func (r *Runner) Reconcile(pid int, bundle string) error {
	digest := bundleDigest(bundle)

	if !r.attached {
		if err := r.runtime.Attach(pid, bundle); err != nil {
			return fmt.Errorf("attach pid %d: %w", pid, err)
		}

		r.currentPID = pid
		r.currentDigest = digest
		r.attached = true
		if r.observer != nil {
			r.observer.Attached(pid)
		}

		return nil
	}

	if pid == r.currentPID && digest == r.currentDigest {
		return nil
	}

	if err := r.runtime.UnloadAndDetach(); err != nil {
		return fmt.Errorf("detach pid %d: %w", r.currentPID, err)
	}

	r.attached = false
	r.currentPID = 0
	r.currentDigest = 0
	if r.observer != nil {
		r.observer.Detached()
	}

	if err := r.runtime.Attach(pid, bundle); err != nil {
		return fmt.Errorf("reattach pid %d: %w", pid, err)
	}

	r.currentPID = pid
	r.currentDigest = digest
	r.attached = true
	if r.observer != nil {
		r.observer.Attached(pid)
	}

	return nil
}

func (r *Runner) Shutdown() error {
	if !r.attached {
		return nil
	}

	if err := r.runtime.UnloadAndDetach(); err != nil {
		return fmt.Errorf("shutdown detach pid %d: %w", r.currentPID, err)
	}

	r.attached = false
	r.currentPID = 0
	r.currentDigest = 0
	if r.observer != nil {
		r.observer.Detached()
	}

	return nil
}
```

- [ ] **Step 4: 전체 lifecycle 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/lifecycle/ -v`
Expected: 6개 테스트 전부 PASS (기존 4개 + 신규 2개)

- [ ] **Step 5: Commit**

```bash
git add frida/daemon/internal/lifecycle/runner.go frida/daemon/internal/lifecycle/runner_test.go
git commit -m "same-PID bundle reload 지원 — FNV-1a digest 기반 변경 감지, detach/reattach 수행"
```

---

### Task 3: P3 — build error caching (fail-closed)

**설계 의도:** source 변경 후 빌드 실패 시 이전 bundle을 제공하지 않는다 (fail-closed). Frida agent는 KakaoTalk obfuscated field 매핑에 종속되어, source가 변경된 이후의 이전 bundle은 silent corruption 위험이 있다. `bundleCache`는 에러 발생 시 `ready=false`로 설정하여 이전 bundle 접근을 차단한다.

**Files:**
- Modify: `frida/daemon/internal/app/run.go:29-52` (bundleCache)
- Modify: `frida/daemon/internal/app/run_test.go`

- [ ] **Step 1: build error 캐싱 테스트 작성**

`frida/daemon/internal/app/run_test.go`에 추가:
```go
func TestBundleCacheSuppressesRebuildForCachedError(t *testing.T) {
	builder := &fakeBundleBuilder{err: errors.New("compile error")}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	_, err1 := cache.get(t.Context(), cfg, builder, versioner)
	if err1 == nil {
		t.Fatal("expected error on first build")
	}

	_, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 == nil {
		t.Fatal("expected cached error on second build")
	}

	_, _, buildCalls := builder.Snapshot()
	if buildCalls != 1 {
		t.Fatalf("Build calls = %d, want 1 (error should be cached)", buildCalls)
	}
}

func TestBundleCacheRetryBuildAfterSourceChange(t *testing.T) {
	builder := &fakeBundleBuilder{err: errors.New("compile error")}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	_, err1 := cache.get(t.Context(), cfg, builder, versioner)
	if err1 == nil {
		t.Fatal("expected error on first build")
	}

	builder.mu.Lock()
	builder.err = nil
	builder.bundle = "fixed-bundle"
	builder.mu.Unlock()
	versioner.Advance()

	bundle, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 != nil {
		t.Fatalf("expected success after source change: %v", err2)
	}

	if bundle != "fixed-bundle" {
		t.Fatalf("bundle = %q, want %q", bundle, "fixed-bundle")
	}

	_, _, buildCalls := builder.Snapshot()
	if buildCalls != 2 {
		t.Fatalf("Build calls = %d, want 2", buildCalls)
	}
}

func TestBundleCacheDoesNotReturnStaleBundleAfterBuildError(t *testing.T) {
	builder := &fakeBundleBuilder{bundle: "good-bundle"}
	versioner := &fakeSourceVersioner{version: 1}
	cfg := Config{AgentProjectRoot: "/agent", AgentEntryPoint: "/agent/entry.ts"}

	cache := &bundleCache{}

	bundle, err := cache.get(t.Context(), cfg, builder, versioner)
	if err != nil {
		t.Fatalf("first build: %v", err)
	}

	if bundle != "good-bundle" {
		t.Fatalf("bundle = %q, want %q", bundle, "good-bundle")
	}

	builder.mu.Lock()
	builder.err = errors.New("compile error")
	builder.bundle = ""
	builder.mu.Unlock()
	versioner.Advance()

	_, err2 := cache.get(t.Context(), cfg, builder, versioner)
	if err2 == nil {
		t.Fatal("expected error after source change + build failure, not stale bundle")
	}
}
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run "TestBundleCache" -v`
Expected: `TestBundleCacheSuppressesRebuildForCachedError` FAIL (현재는 에러 캐싱 없이 매번 빌드)

- [ ] **Step 3: bundleCache에 에러 캐싱 구현**

`frida/daemon/internal/app/run.go` bundleCache 변경:
```go
type bundleCache struct {
	ready         bool
	bundle        string
	sourceVersion uint64
	lastError     error
	errorVersion  uint64
}

func (c *bundleCache) get(ctx context.Context, cfg Config, builder BundleBuilder, versioner sourceVersioner) (string, error) {
	sourceVersion := versioner.Version()

	if c.ready && c.sourceVersion == sourceVersion {
		return c.bundle, nil
	}

	if c.lastError != nil && c.errorVersion == sourceVersion {
		return "", c.lastError
	}

	bundle, err := builder.Build(ctx, cfg.AgentProjectRoot, cfg.AgentEntryPoint)
	if err != nil {
		c.lastError = fmt.Errorf("build agent bundle: %w", err)
		c.errorVersion = sourceVersion
		// fail-closed: source 변경 후 빌드 실패 시 이전 bundle 접근 차단
		c.ready = false
		return "", c.lastError
	}

	c.bundle = bundle
	c.ready = true
	c.sourceVersion = sourceVersion
	c.lastError = nil

	return c.bundle, nil
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run "TestBundleCache" -v`
Expected: 3개 신규 테스트 PASS

- [ ] **Step 5: 기존 테스트 회귀 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -v`
Expected: 전체 PASS

- [ ] **Step 6: Commit**

```bash
git add frida/daemon/internal/app/run.go frida/daemon/internal/app/run_test.go
git commit -m "bundleCache build error 캐싱 — fail-closed, source 미변경 시 재빌드 차단"
```

---

### Task 4: P2+P4 — adaptive polling + state-transition logging

**설계 의도:**
- `nextPollInterval(state)`은 **성공 후 happy path 전용**. 모든 실패(PID/build/reconcile)는 `retryDelay` 사용 (fast retry). build error는 P3 캐싱으로 재빌드 차단되므로 fast retry 비용 최소.
- 상태 전이 로그: `lastState` 추적으로 state transition을 명시적으로 로깅. 매 poll 반복 로그 제거.

**전제 조건:** Task 1~3 완료 (특히 Task 1의 `fakeRuntime.Available()` 추가가 컴파일에 필수)

**Files:**
- Modify: `frida/daemon/internal/app/readiness.go` (CurrentState 추가)
- Modify: `frida/daemon/internal/app/readiness_test.go`
- Modify: `frida/daemon/internal/app/run.go` (루프 전환 + 로그 교체)
- Modify: `frida/daemon/internal/app/run_test.go`
- Modify: `frida/daemon/cmd/graft-daemon/main.go`

#### Step Group A: ReadinessTracker.CurrentState()

- [ ] **Step A1: CurrentState 테스트 작성**

`frida/daemon/internal/app/readiness_test.go`에 추가:
```go
func TestReadinessTrackerCurrentStateReflectsLifecycle(t *testing.T) {
	now := time.Unix(1711363200, 0)
	tracker := NewReadinessTracker(ReadinessOptions{
		HeartbeatTTL: 3 * time.Second,
		Now:          func() time.Time { return now },
	})

	if got := tracker.CurrentState(); got != StateBooting {
		t.Fatalf("initial CurrentState = %q, want %q", got, StateBooting)
	}

	tracker.ObserveAttach(4321)

	if got := tracker.CurrentState(); got != StateHooking {
		t.Fatalf("after attach CurrentState = %q, want %q", got, StateHooking)
	}

	tracker.ObserveScriptMessage(`{"type":"send","payload":"{\"type\":\"graft-health\",\"event\":\"heartbeat\",\"state\":\"READY\",\"ready\":true,\"hooks\":{\"b6\":true,\"o\":true,\"t\":true,\"A\":true,\"u\":true},\"timestampMs\":1711363200000}"}`)

	if got := tracker.CurrentState(); got != StateReady {
		t.Fatalf("after health CurrentState = %q, want %q", got, StateReady)
	}

	// heartbeat stale -> DEGRADED 반영 확인
	now = now.Add(4 * time.Second)

	if got := tracker.CurrentState(); got != StateDegraded {
		t.Fatalf("after stale heartbeat CurrentState = %q, want %q", got, StateDegraded)
	}

	tracker.ObserveDetach()

	if got := tracker.CurrentState(); got != StateBooting {
		t.Fatalf("after detach CurrentState = %q, want %q", got, StateBooting)
	}
}
```

- [ ] **Step A2: CurrentState 구현**

`Snapshot().State`를 사용하여 heartbeat staleness, missing hooks 등 computed state를 반영한다. `baseState`만 반환하면 heartbeat stale 시 `StateReady`가 유지되어 poll 주기가 잘못됨.

`frida/daemon/internal/app/readiness.go`에 추가 (Snapshot 메서드 앞):
```go
func (t *ReadinessTracker) CurrentState() ReadinessState {
	return t.Snapshot().State
}
```

- [ ] **Step A3: readiness 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run "TestReadiness" -v`
Expected: 전체 PASS (기존 + 신규 1개)

#### Step Group B: nextPollInterval

- [ ] **Step B1: nextPollInterval 테스트 작성**

`frida/daemon/internal/app/run_test.go`에 추가:
```go
func TestNextPollInterval(t *testing.T) {
	tests := []struct {
		state   ReadinessState
		baseSec int
		want    time.Duration
	}{
		{StateReady, 30, 30 * time.Second},
		{StateBooting, 30, 5 * time.Second},
		{StateHooking, 30, 2 * time.Second},
		{StateDegraded, 30, 2 * time.Second},
		{StateBlocked, 30, 10 * time.Second},
		{StateWarm, 30, 5 * time.Second},
		{StateReady, 1, 1 * time.Second},
		{StateBooting, 1, 1 * time.Second},
	}

	for _, tt := range tests {
		got := nextPollInterval(tt.state, tt.baseSec)
		if got != tt.want {
			t.Errorf("nextPollInterval(%q, %d) = %s, want %s", tt.state, tt.baseSec, got, tt.want)
		}
	}
}
```

- [ ] **Step B2: nextPollInterval 구현**

`frida/daemon/internal/app/run.go`에 추가:
```go
func nextPollInterval(state ReadinessState, baseSeconds int) time.Duration {
	base := time.Duration(baseSeconds) * time.Second

	switch state {
	case StateReady:
		return base
	case StateBooting:
		return min(5*time.Second, base)
	case StateHooking:
		return min(2*time.Second, base)
	case StateDegraded:
		return min(2*time.Second, base)
	case StateBlocked:
		return min(10*time.Second, base)
	case StateWarm:
		return min(5*time.Second, base)
	default:
		return min(5*time.Second, base)
	}
}
```

- [ ] **Step B3: nextPollInterval 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run "TestNextPollInterval" -v`
Expected: PASS

#### Step Group C: run 루프 전환 + 상태 전이 로그

- [ ] **Step C1: Run/runWithSourceVersioner 시그니처 변경 + 루프 전환**

`frida/daemon/internal/app/run.go` — `Run` 시그니처에 stateFunc 추가:
```go
func Run(ctx context.Context, cfg Config, pidFinder PIDFinder, builder BundleBuilder, reconciler Reconciler, stateFunc func() ReadinessState) error {
```

`runWithSourceVersioner` 시그니처에 stateFunc 추가:
```go
func runWithSourceVersioner(
	ctx context.Context,
	cfg Config,
	pidFinder PIDFinder,
	builder BundleBuilder,
	reconciler Reconciler,
	versioner sourceVersioner,
	stateFunc func() ReadinessState,
) error {
```

`Run` 내부 호출부 변경:
```go
if err := runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, versioner, stateFunc); err != nil {
```

`runWithSourceVersioner` 본문을 다음으로 전체 교체:
```go
func runWithSourceVersioner(
	ctx context.Context,
	cfg Config,
	pidFinder PIDFinder,
	builder BundleBuilder,
	reconciler Reconciler,
	versioner sourceVersioner,
	stateFunc func() ReadinessState,
) error {
	defer func() {
		if err := reconciler.Shutdown(); err != nil {
			log.Printf("reconciler shutdown failed: %v", err)
		}
	}()

	retryDelay := time.Duration(cfg.RetryDelaySeconds) * time.Second
	if retryDelay < minRetryDelay {
		retryDelay = minRetryDelay
	}

	cache := &bundleCache{}

	pollTimer := time.NewTimer(0)
	defer pollTimer.Stop()

	var lastPID int
	var lastBundle string
	var lastState ReadinessState

	for {
		select {
		case <-ctx.Done():
			return nil
		case <-pollTimer.C:
		}

		pid, err := lookupPIDWithTimeout(ctx, cfg, pidFinder)
		if err != nil {
			log.Printf("pid lookup failed for device=%s: %v", cfg.DeviceID, err)
			// 실패 시 fast retry — state-based 아님
			pollTimer.Reset(retryDelay)
			continue
		}

		bundle, bundleErr := cache.get(ctx, cfg, builder, versioner)
		if bundleErr != nil {
			log.Printf("bundle build failed for agent=%s: %v", cfg.AgentEntryPoint, bundleErr)
			// 실패 시 fast retry — P3 캐싱으로 재빌드 차단, fast retry 비용 최소
			pollTimer.Reset(retryDelay)
			continue
		}

		if runErr := reconciler.Reconcile(pid, bundle); runErr != nil {
			log.Printf("reconcile failed for pid=%d agent=%s: %v", pid, cfg.AgentEntryPoint, runErr)
			// 실패 시 fast retry
			pollTimer.Reset(retryDelay)
			continue
		}

		// 상태 전이 로그 — 매 poll 반복 로그 대신 이벤트 기반
		if pid != lastPID {
			log.Printf("attached pid=%d agent=%s", pid, cfg.AgentEntryPoint)
			lastPID = pid
			lastBundle = bundle
		} else if bundle != lastBundle {
			log.Printf("reloaded bundle for pid=%d agent=%s", pid, cfg.AgentEntryPoint)
			lastBundle = bundle
		}

		state := stateFunc()
		if state != lastState {
			log.Printf("state %s -> %s pid=%d", lastState, state, pid)
			lastState = state
		}

		// 성공 후 happy path — 상태 기반 adaptive polling
		pollTimer.Reset(nextPollInterval(state, cfg.PidPollIntervalSeconds))
	}
}
```

`waitRetry` 함수 삭제 (더 이상 사용하지 않음).

- [ ] **Step C2: 기존 테스트에 stateFunc 파라미터 추가**

`run_test.go`의 기존 테스트 호출부 변경:

`TestRunReturnsOnContextCancellationAfterShutdown`:
```go
// 변경 전
if err := Run(ctx, cfg, pidFinder, builder, reconciler); err != nil {
// 변경 후
if err := Run(ctx, cfg, pidFinder, builder, reconciler, func() ReadinessState { return StateReady }); err != nil {
```

`TestRunRetriesAfterReconcileFailure`:
```go
// 변경 전
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1})
// 변경 후
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1}, func() ReadinessState { return StateReady })
```

`TestRunUsesBoundedPIDLookupContext`:
```go
// 변경 전
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1})
// 변경 후
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, &fakeSourceVersioner{version: 1}, func() ReadinessState { return StateReady })
```

`blockingPIDFinder` — 새 루프가 PID 실패 후 fast retry하므로 `timedOut`이 이중 close되어 panic 발생. `sync.Once` 추가:
```go
type blockingPIDFinder struct {
	started      chan struct{}
	timedOut     chan struct{}
	once         sync.Once
	timedOutOnce sync.Once
}

func (f *blockingPIDFinder) LookupPID(ctx context.Context, _ string) (int, error) {
	f.once.Do(func() {
		close(f.started)
	})

	<-ctx.Done()
	f.timedOutOnce.Do(func() {
		close(f.timedOut)
	})

	return 0, fmt.Errorf("blocking pid finder canceled: %w", ctx.Err())
}
```

`TestRunRebuildsBundleAfterAgentSourceChanges`:
```go
// 변경 전
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, sourceVersioner)
// 변경 후
done <- runWithSourceVersioner(ctx, cfg, pidFinder, builder, reconciler, sourceVersioner, func() ReadinessState { return StateReady })
```

`TestWaitRetryZeroDelayStillYields` — 삭제 (waitRetry 함수 제거에 따라 불필요).

- [ ] **Step C3: main.go에 stateFunc 전달**

`frida/daemon/cmd/graft-daemon/main.go` 변경:
```go
// 변경 전
if err := app.Run(ctx, cfg, pidFinder, bundler, reconciler); err != nil {
// 변경 후
if err := app.Run(ctx, cfg, pidFinder, bundler, reconciler, readiness.CurrentState); err != nil {
```

- [ ] **Step C4: 전체 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./... -v`
Expected: 전체 PASS

- [ ] **Step C5: 빌드 검증**

Run: `cd frida/daemon && GOWORK=off go build ./...`
Expected: 성공

- [ ] **Step C6: Commit**

```bash
git add frida/daemon/internal/app/readiness.go frida/daemon/internal/app/readiness_test.go frida/daemon/internal/app/run.go frida/daemon/internal/app/run_test.go frida/daemon/cmd/graft-daemon/main.go
git commit -m "adaptive polling + state-transition logging — happy path만 상태 기반, 실패는 fast retry, 상태 전이 로그 추가"
```
