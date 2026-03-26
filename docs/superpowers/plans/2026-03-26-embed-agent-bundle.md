# Go Daemon JS Agent Bundle Embed 구현 계획

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Go daemon 바이너리에 JS agent bundle을 `go:embed`로 내장하여 ~580줄 dead code + `fsnotify` 의존성 제거

**Architecture:** `agentbundle` 패키지가 embedded JS를 제공하고, `app.BundleSource` 인터페이스가 `BundleBuilder` + `bundleCache` + `sourceVersioner`를 대체. `--agent-override` fallback으로 개발/긴급 파일 읽기 지원.

**Tech Stack:** Go 1.26, `embed` stdlib, FNV-1a digest (기존 유지)

**Spec:** `docs/superpowers/specs/2026-03-26-embed-agent-bundle-design.md`

---

### Task 1: agentbundle 패키지 생성

JS 파일을 daemon 소스 트리에 복사하고, embed 패키지를 생성한다.

**Files:**
- Create: `frida/daemon/internal/agentbundle/bundles/` (JS 파일 복사)
- Create: `frida/daemon/internal/agentbundle/bundle.go`
- Create: `frida/daemon/internal/agentbundle/bundle_test.go`

**Context:**
- 현재 JS 산출물 위치: `frida/agent/generated/thread-image-graft.js` (~500KB), `frida/agent/generated/thread-markdown-graft.js` (~4KB)
- `agentbundle`은 `app` 패키지를 import하지 않는다 (implicit interface satisfaction)
- Spec의 `agentbundle` 코드 예시: spec 라인 62-112

- [ ] **Step 1: JS 파일 복사**

```bash
mkdir -p frida/daemon/internal/agentbundle/bundles
cp frida/agent/generated/thread-image-graft.js frida/daemon/internal/agentbundle/bundles/
cp frida/agent/generated/thread-markdown-graft.js frida/daemon/internal/agentbundle/bundles/
```

- [ ] **Step 2: bundle_test.go 작성 (실패 테스트)**

```go
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
```

- [ ] **Step 3: 테스트 실패 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/agentbundle/...`
Expected: FAIL (패키지 미존재)

- [ ] **Step 4: bundle.go 구현**

```go
package agentbundle

import (
	"embed"
	"fmt"
	"io/fs"
	"sort"
	"strings"
)

//go:embed bundles/*.js
var bundles embed.FS

// StaticSource는 embedded JS bundle을 제공한다.
type StaticSource struct {
	name    string
	content string
}

func (s *StaticSource) Bundle() (string, error) { return s.content, nil }
func (s *StaticSource) String() string           { return "embedded:" + s.name }

// Source는 embedded bundle에서 name에 해당하는 StaticSource를 반환한다.
func Source(name string) (*StaticSource, error) {
	data, err := bundles.ReadFile("bundles/" + name + ".js")
	if err != nil {
		return nil, fmt.Errorf("agent %q not found (available: %v): %w",
			name, Available(), err)
	}
	return &StaticSource{name: name, content: string(data)}, nil
}

// Available는 embedded bundle 이름 목록을 반환한다.
func Available() []string {
	entries, err := fs.ReadDir(bundles, "bundles")
	if err != nil {
		return nil
	}
	var names []string
	for _, e := range entries {
		if !e.IsDir() && strings.HasSuffix(e.Name(), ".js") {
			names = append(names, strings.TrimSuffix(e.Name(), ".js"))
		}
	}
	sort.Strings(names)
	return names
}
```

- [ ] **Step 5: 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/agentbundle/...`
Expected: PASS

- [ ] **Step 6: 커밋**

```bash
git add frida/daemon/internal/agentbundle/
git commit -m "feat(frida): agentbundle 패키지 — go:embed JS bundle 제공"
```

---

### Task 2: BundleSource 인터페이스 + FileBundleSource

`app` 패키지에 `BundleSource` 인터페이스와 `FileBundleSource` 구현체를 추가한다.

**Files:**
- Create: `frida/daemon/internal/app/bundle_source.go`
- Create: `frida/daemon/internal/app/bundle_source_test.go`

**Context:**
- `BundleSource`는 `run.go`에 정의해도 되지만, 테스트 격리를 위해 별도 파일로 분리
- `FileBundleSource`는 매 호출마다 `os.ReadFile` (poll cycle당 1회, 2~30초 간격)
- Spec 라인 48-53 (인터페이스), 119-139 (FileBundleSource)

- [ ] **Step 1: bundle_source_test.go 작성**

```go
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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run TestFileBundleSource -v`
Expected: FAIL

- [ ] **Step 3: bundle_source.go 구현**

```go
package app

import (
	"fmt"
	"os"
)

// BundleSource는 JS agent bundle을 제공한다.
type BundleSource interface {
	Bundle() (string, error)
	String() string
}

// FileBundleSource는 파일시스템에서 bundle을 읽는다.
type FileBundleSource struct {
	path string
}

func NewFileBundleSource(path string) *FileBundleSource {
	return &FileBundleSource{path: path}
}

func (f *FileBundleSource) Bundle() (string, error) {
	data, err := os.ReadFile(f.path)
	if err != nil {
		return "", fmt.Errorf("read override bundle %s: %w", f.path, err)
	}
	return string(data), nil
}

func (f *FileBundleSource) String() string {
	return "file:" + f.path
}
```

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run TestFileBundleSource -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frida/daemon/internal/app/bundle_source.go frida/daemon/internal/app/bundle_source_test.go
git commit -m "feat(frida): BundleSource 인터페이스 + FileBundleSource 구현"
```

---

### Task 3: Config 변경 — AgentName + AgentOverride

`ParseConfig`를 재작성하여 `AgentProjectRoot`/`AgentEntryPoint`를 `AgentName`/`AgentOverride`로 대체한다.

**Files:**
- Modify: `frida/daemon/internal/app/config.go`
- Modify: `frida/daemon/internal/app/config_test.go`

**Context:**
- `--agent`는 필수 (embedded bundle 이름), `--agent-override`는 선택 (파일 경로)
- `ResolveRepoRoot()` 제거 — `repoRoot` 파라미터 불필요
- `ParseConfig(repoRoot, args)` → `ParseConfig(args)`
- 기존 `config.go:23-75`, `config_test.go:1-100` 참조
- Spec 라인 146-167

- [ ] **Step 1: config_test.go 재작성**

기존 테스트를 새 Config 구조에 맞게 전면 재작성:

```go
package app

import "testing"

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
```

- [ ] **Step 2: 테스트 실패 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run TestParseConfig -v`
Expected: FAIL (시그니처 불일치)

- [ ] **Step 3: config.go 재작성**

```go
package app

import (
	"errors"
	"flag"
	"fmt"
)

type Config struct {
	AgentName              string
	AgentOverride          string
	DeviceID               string
	FridaCoreDevkitDir     string
	ReadinessAddr          string
	HeartbeatTimeoutSeconds int
	PidPollIntervalSeconds  int
	RetryDelaySeconds       int
}

func ParseConfig(args []string) (Config, error) {
	fs := flag.NewFlagSet("graft-daemon", flag.ContinueOnError)
	agent := fs.String("agent", "", "embedded agent bundle name")
	agentOverride := fs.String("agent-override", "", "override bundle file path")
	device := fs.String("device", "emulator-5554", "adb device id")
	devkit := fs.String("frida-core-devkit", "", "frida core devkit directory")
	readinessAddr := fs.String("readiness-addr", "127.0.0.1:17373", "local readiness endpoint bind address")
	heartbeatTimeout := fs.Int("heartbeat-timeout", 15, "agent heartbeat timeout seconds")
	pidPoll := fs.Int("pid-poll-interval", 30, "pid poll interval seconds")
	retryDelay := fs.Int("retry-delay", 5, "retry delay seconds")

	if err := fs.Parse(args); err != nil {
		return Config{}, fmt.Errorf("parse graft-daemon flags: %w", err)
	}

	if *agent == "" {
		return Config{}, errors.New("agent is required")
	}

	return Config{
		AgentName:              *agent,
		AgentOverride:          *agentOverride,
		DeviceID:               *device,
		FridaCoreDevkitDir:     *devkit,
		ReadinessAddr:          *readinessAddr,
		HeartbeatTimeoutSeconds: *heartbeatTimeout,
		PidPollIntervalSeconds:  *pidPoll,
		RetryDelaySeconds:       *retryDelay,
	}, nil
}
```

**주의**: `ResolveRepoRoot()` 함수는 아직 삭제하지 않는다. main.go가 아직 참조하므로 Task 5에서 처리.

- [ ] **Step 4: 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -run TestParseConfig -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frida/daemon/internal/app/config.go frida/daemon/internal/app/config_test.go
git commit -m "refactor(frida): Config에서 AgentProjectRoot/EntryPoint → AgentName/Override"
```

---

### Task 4: Run() 재작성 + 테스트 마이그레이션

`Run()`에서 `bundleCache`, `sourceVersioner`, `BundleBuilder`를 제거하고 `BundleSource`를 사용한다. 테스트를 전면 마이그레이션한다.

**Files:**
- Modify: `frida/daemon/internal/app/run.go`
- Modify: `frida/daemon/internal/app/run_test.go`

**Context:**
- 현재 `run.go:16-18` (`BundleBuilder`), `run.go:29-62` (`bundleCache`), `run.go:108-119` (`RunOnce`), `run.go:121-145` (`Run`), `run.go:147-225` (`runWithSourceVersioner`)
- 현재 `run_test.go:16-42` (`fakeBundleBuilder`), `run_test.go:181-202` (`fakeSourceVersioner`), `run_test.go:204-286` (bundleCache 테스트 3개), `run_test.go:459-551` (sourceVersioner 테스트 2개)
- Spec 라인 196-285 (Run 전체 코드)

- [ ] **Step 1: run_test.go에서 fakeBundleSource 추가 + 기존 fake 유지 (점진적)**

테스트 파일 상단에 `fakeBundleSource` 추가:

```go
type fakeBundleSource struct {
	mu     sync.Mutex
	bundle string
	err    error
	calls  int
}

func (f *fakeBundleSource) Bundle() (string, error) {
	f.mu.Lock()
	defer f.mu.Unlock()
	f.calls++
	return f.bundle, f.err
}

func (f *fakeBundleSource) String() string { return "fake:test" }

func (f *fakeBundleSource) CallCount() int {
	f.mu.Lock()
	defer f.mu.Unlock()
	return f.calls
}
```

- [ ] **Step 2: run.go 재작성**

`run.go`에서 다음을 수행:
1. `BundleBuilder` 인터페이스 제거
2. `bundleCache` struct + `get()` 제거
3. `RunOnce()` 제거
4. `Run()` 시그니처를 `Run(ctx, cfg, pidFinder, reconciler, stateFunc, source BundleSource)` 로 변경
5. `runWithSourceVersioner()` 내부 함수 제거 — 로직을 `Run()`에 직접 통합
6. `sourceVersioner` 생성/정리 코드 제거
7. `source.Bundle()` 직접 호출 + 연속 에러 suppression 추가

Spec 라인 196-285의 `Run()` 코드를 그대로 사용. `sourceVersioner` 관련 import (`fmt` 제외하고는 동일)도 정리.

- [ ] **Step 3: run_test.go 마이그레이션**

삭제 대상 (전체 제거):
- `fakeBundleBuilder` (라인 16-42)
- `writeTempAgentProject` (라인 88-99)
- `TestRunOnceBuildsBundleAndReconcilesPID` (라인 101-124)
- `fakeSourceVersioner` (라인 181-202)
- `TestBundleCacheSuppressesRebuildForCachedError` (라인 204-225)
- `TestBundleCacheRetryBuildAfterSourceChange` (라인 227-258)
- `TestBundleCacheDoesNotReturnStaleBundleAfterBuildError` (라인 260-286)
- `TestRunRebuildsBundleAfterAgentSourceChanges` (라인 459-519)
- `TestSourceVersionerBumpsAfterRelevantFileChange` (라인 521-551)

수정 대상 (BundleSource 시그니처로 변경):
- `TestRunReturnsOnContextCancellationAfterShutdown` (라인 314-338): `builder` → `source`, `Run()` 시그니처
- `TestRunRetriesAfterReconcileFailure` (라인 340-387): `runWithSourceVersioner` → `Run()`, `builder` → `source`
- `TestRunUsesBoundedPIDLookupContext` (라인 389-433): 동일

기존 `Config` 필드 참조도 `AgentName`으로 변경. `AgentProjectRoot`/`AgentEntryPoint` → 제거.

- [ ] **Step 4: 전체 테스트 통과 확인**

Run: `cd frida/daemon && GOWORK=off go test ./internal/app/ -v`
Expected: PASS

- [ ] **Step 5: 커밋**

```bash
git add frida/daemon/internal/app/run.go frida/daemon/internal/app/run_test.go
git commit -m "refactor(frida): Run()에서 BundleSource 사용, bundleCache/sourceVersioner 제거"
```

---

### Task 5: main.go 와이어링 + dead code 삭제

`main.go`를 새 구조에 맞게 재작성하고, 불필요한 코드/패키지/의존성을 삭제한다.

**Files:**
- Modify: `frida/daemon/cmd/graft-daemon/main.go`
- Delete: `frida/daemon/internal/agentbuild/` (5 files)
- Delete: `frida/daemon/internal/app/source_versioner.go`
- Delete: `frida/daemon/internal/app/source_versioner_test.go`
- Modify: `frida/daemon/internal/app/config.go` (ResolveRepoRoot 제거)
- Modify: `frida/daemon/go.mod` + `frida/daemon/go.sum` (fsnotify 제거)

**Context:**
- 현재 `main.go:25-66` 전체 재작성
- `agentbuild` import 제거, `agentbundle` import 추가
- `ResolveRepoRoot()` 호출 제거, `ParseConfig(args)` 시그니처
- `--agent-override` 존재 시 `os.Stat` fail-fast
- Spec 라인 171-191 (main.go 와이어링)

- [ ] **Step 1: main.go 재작성**

```go
package main

import (
	"context"
	"fmt"
	"log"
	"os"
	"os/signal"
	"syscall"
	"time"

	"github.com/park285/Iris/frida/daemon/internal/adb"
	"github.com/park285/Iris/frida/daemon/internal/agentbundle"
	"github.com/park285/Iris/frida/daemon/internal/app"
	"github.com/park285/Iris/frida/daemon/internal/fridaapi"
	"github.com/park285/Iris/frida/daemon/internal/lifecycle"
)

func main() {
	if err := run(); err != nil {
		log.Fatal(err)
	}
}

func run() error {
	cfg, err := app.ParseConfig(os.Args[1:])
	if err != nil {
		return fmt.Errorf("parse config: %w", err)
	}

	// BundleSource 결정
	var source app.BundleSource
	if cfg.AgentOverride != "" {
		if _, err := os.Stat(cfg.AgentOverride); err != nil {
			return fmt.Errorf("agent override file: %w", err)
		}
		source = app.NewFileBundleSource(cfg.AgentOverride)
	} else {
		s, err := agentbundle.Source(cfg.AgentName)
		if err != nil {
			return fmt.Errorf("resolve agent bundle: %w", err)
		}
		source = s
	}

	log.Printf("agent=%s source=%s", cfg.AgentName, source)

	ctx, stop := signal.NotifyContext(context.Background(), syscall.SIGINT, syscall.SIGTERM)
	defer stop()

	readiness := app.NewReadinessTracker(app.ReadinessOptions{
		HeartbeatTTL: time.Duration(cfg.HeartbeatTimeoutSeconds) * time.Second,
	})
	if _, err := app.StartReadinessServer(ctx, cfg.ReadinessAddr, readiness); err != nil {
		return fmt.Errorf("start readiness server: %w", err)
	}

	pidFinder := adb.NewService()
	runtime := fridaapi.NewRuntime(cfg.DeviceID, cfg.FridaCoreDevkitDir, func(message string) {
		readiness.ObserveScriptMessage(message)
	})
	if !runtime.Available() {
		return fmt.Errorf("frida runtime backend is not available; rebuild with -tags frida_core")
	}
	reconciler := lifecycle.NewRunner(runtime, readiness)

	if err := app.Run(ctx, cfg, pidFinder, reconciler, readiness.CurrentState, source); err != nil {
		return fmt.Errorf("run daemon: %w", err)
	}

	return nil
}
```

- [ ] **Step 2: config.go에서 ResolveRepoRoot 제거**

`config.go`에서 `ResolveRepoRoot()` 함수와 관련 import (`os`, `path/filepath`, `strings`) 정리. 사용하지 않는 import만 제거.

- [ ] **Step 3: agentbuild 패키지 삭제**

```bash
rm -rf frida/daemon/internal/agentbuild/
```

- [ ] **Step 4: source_versioner 삭제**

```bash
rm frida/daemon/internal/app/source_versioner.go
rm frida/daemon/internal/app/source_versioner_test.go
```

- [ ] **Step 5: go.mod에서 fsnotify 제거**

```bash
cd frida/daemon && GOWORK=off go mod tidy
```

- [ ] **Step 6: 전체 빌드 + 테스트 확인**

```bash
cd frida/daemon && GOWORK=off go build ./... && GOWORK=off go test ./...
```
Expected: BUILD SUCCESS + ALL TESTS PASS

- [ ] **Step 7: 커밋**

```bash
git add -A frida/daemon/
git commit -m "refactor(frida): dead code 제거 — agentbuild, sourceVersioner, fsnotify

main.go를 agentbundle + BundleSource 기반으로 재작성.
agentbuild 패키지 (5 files), source_versioner, RunOnce, ResolveRepoRoot,
bundleCache, BundleBuilder 제거. fsnotify 의존성 제거."
```

---

### Task 6: Makefile + .gitattributes + 최종 정리

**Files:**
- Modify: `frida/daemon/Makefile`
- Create: `frida/daemon/.gitattributes`

- [ ] **Step 1: Makefile 업데이트**

`frida/daemon/Makefile`에 `sync-bundles`와 `build-fresh` 추가:

```makefile
GO ?= go
GOLANGCI_LINT ?= golangci-lint
BUNDLE_DIR := internal/agentbundle/bundles

.PHONY: fmt
fmt:
	GOWORK=off $(GOLANGCI_LINT) fmt
	GOWORK=off $(GOLANGCI_LINT) run --fix ./...

.PHONY: lint
lint:
	GOWORK=off $(GOLANGCI_LINT) run ./...

.PHONY: test
test:
	GOWORK=off $(GO) test ./...

.PHONY: test-race
test-race:
	GOWORK=off $(GO) test -race ./...

.PHONY: vet
vet:
	GOWORK=off $(GO) vet ./...

.PHONY: sync-bundles
sync-bundles:
	@test -d ../agent/generated || (echo "ERROR: agent generated/ not found" && exit 1)
	@ls ../agent/generated/*.js >/dev/null 2>&1 || (echo "ERROR: no .js bundles" && exit 1)
	rm -f $(BUNDLE_DIR)/*.js
	cp ../agent/generated/*.js $(BUNDLE_DIR)/

.PHONY: build
build: lint
	GOWORK=off $(GO) build ./...

.PHONY: build-fresh
build-fresh: lint sync-bundles
	GOWORK=off $(GO) build ./...
```

- [ ] **Step 2: .gitattributes 생성**

```
internal/agentbundle/bundles/*.js binary
```

- [ ] **Step 3: 전체 테스트 최종 확인**

```bash
cd frida/daemon && GOWORK=off go test ./... && GOWORK=off go vet ./...
```
Expected: ALL PASS

- [ ] **Step 4: 커밋**

```bash
git add frida/daemon/Makefile frida/daemon/.gitattributes
git commit -m "chore(frida): Makefile sync-bundles/build-fresh + .gitattributes binary"
```
