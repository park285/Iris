# Go Daemon JS Agent Bundle Embed 설계

## 목표

Go daemon(graft-daemon) 바이너리에 JS agent bundle을 `go:embed`로 내장하여, runtime 파일 읽기/감시 인프라(`sourceVersioner`, `bundleCache`, `agentbuild` 패키지, `fsnotify` 의존성)를 제거한다.

## 배경

현재 production 경로에서 다음 구성요소가 사실상 dead code이다:

- **`sourceVersioner`** (fsnotify): `frida/agent/` 감시하나 `generated/` 디렉토리를 명시적으로 무시 (`source_versioner.go:197-203`). production entry point인 `generated/thread-image-graft.js`의 변경을 감지하지 못함.
- **`bundleCache`**: `sourceVersion`이 불변이므로 첫 `Build()` 이후 영구 캐시 적중.
- **`agentbuild` 패키지**: production에서 `fridaCompilerBundler.Build()`는 `.js` suffix → `os.ReadFile()` 단일 경로만 사용 (`bundler_real.go:16-23`).

이 구조는 ~550줄의 코드 + `fsnotify` 외부 의존성 + build tag 분기(`frida_core` / `!frida_core`)를 유지하면서 production에서 아무 가치를 제공하지 않는다. 또한 startup 시 fsnotify watcher 생성 실패가 daemon 전체 실패로 이어지는 불필요한 실패 표면을 만든다.

## 설계

### 접근법: Embed + Override Fallback

- **기본 경로 (production)**: JS bundle을 `go:embed`로 바이너리에 내장. I/O 없음.
- **Override 경로 (개발/긴급)**: `--agent-override <path>` 플래그로 파일시스템에서 읽기. Runner의 FNV-1a digest가 자연 hot-reload 제공.

### 패키지 구조

```
frida/daemon/
├── cmd/graft-daemon/main.go          # BundleSource 와이어링
├── internal/
│   ├── agentbundle/                   # 신규 패키지
│   │   ├── bundles/                   # repo에 커밋 (gitignore 아님)
│   │   │   ├── thread-image-graft.js  # ~500KB (frida-compile 산출물)
│   │   │   └── thread-markdown-graft.js  # ~4KB
│   │   ├── bundle.go                  # //go:embed + Source()
│   │   └── bundle_test.go
│   ├── app/
│   │   ├── run.go                     # 수정: BundleSource 사용
│   │   ├── config.go                  # 수정: AgentName + AgentOverride
│   │   └── ...
│   ├── lifecycle/runner.go            # 변경 없음
│   └── fridaapi/                      # 변경 없음
```

### BundleSource 인터페이스

`app` 패키지에서 정의. 기존 `BundleBuilder` + `bundleCache` + `sourceVersioner`를 대체.

```go
// internal/app/run.go
type BundleSource interface {
    Bundle() (string, error)
    String() string  // 로그용 식별자
}
```

- `String()`: startup/에러 로그에서 bundle 출처 식별. `"embedded:thread-image-graft"` 또는 `"file:/path/to.js"` 형태.

### agentbundle 패키지

`app` 패키지를 import하지 않는다. Go implicit interface satisfaction(duck typing)으로 `app.BundleSource`를 충족.

```go
// internal/agentbundle/bundle.go
package agentbundle

import (
    "embed"
    "fmt"
    "io/fs"
    "path"
    "sort"
    "strings"
)

//go:embed bundles/*.js
var bundles embed.FS

// StaticSource는 embedded JS bundle을 제공한다.
// app.BundleSource를 implicit하게 충족 (import하지 않음).
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

### FileBundleSource (Override 경로)

`app` 패키지에 정의. 개발/긴급 상황 전용.

```go
// internal/app/bundle_source.go
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

매 poll cycle마다 `os.ReadFile` 호출. Runner의 FNV-1a digest가 변경 감지를 처리하므로 별도 watcher 불필요.

### Config 변경

```go
type Config struct {
    AgentName              string  // embedded bundle 키 (e.g., "thread-image-graft")
    AgentOverride          string  // override file path (빈 값이면 embed 사용)
    DeviceID               string
    FridaCoreDevkitDir     string  // Runtime용 유지
    ReadinessAddr          string
    HeartbeatTimeoutSeconds int
    PidPollIntervalSeconds  int
    RetryDelaySeconds       int
}
```

CLI 플래그:
- `--agent thread-image-graft` — embedded bundle 선택 (기존: `--agent generated/thread-image-graft.js`)
- `--agent-override /path/to.js` — 파일에서 읽기 (신규)

**Breaking change**: `--agent` 의미가 경로에서 이름으로 변경. 단일 배포(KR host systemd unit)이므로 unit 파일 업데이트로 해결.

### main.go 와이어링

```go
// BundleSource 결정
var source app.BundleSource
if cfg.AgentOverride != "" {
    // startup fail-fast: 파일 존재 검증
    if _, err := os.Stat(cfg.AgentOverride); err != nil {
        return fmt.Errorf("agent override file: %w", err)
    }
    source = app.NewFileBundleSource(cfg.AgentOverride)
} else {
    s, err := agentbundle.Source(cfg.AgentName)
    if err != nil {
        return fmt.Errorf("resolve agent bundle: %w", err)
    }
    source = s  // implicit interface satisfaction
}

log.Printf("agent=%s source=%s", cfg.AgentName, source)

app.Run(ctx, cfg, pidFinder, reconciler, readiness.CurrentState, source)
```

### Run() 단순화

```go
func Run(ctx context.Context, cfg Config, pidFinder PIDFinder,
    reconciler Reconciler, stateFunc func() ReadinessState,
    source BundleSource) error {

    // sourceVersioner 생성 제거
    // bundleCache 제거

    defer func() {
        if closer, ok := pidFinder.(interface{ Close() error }); ok {
            if err := closer.Close(); err != nil {
                log.Printf("pid finder shutdown failed: %v", err)
            }
        }
    }()

    defer func() {
        if err := reconciler.Shutdown(); err != nil {
            log.Printf("reconciler shutdown failed: %v", err)
        }
    }()

    if stateFunc == nil {
        stateFunc = func() ReadinessState { return StateBooting }
    }

    retryDelay := max(time.Duration(cfg.RetryDelaySeconds)*time.Second, minRetryDelay)
    pollTimer := time.NewTimer(0)
    defer pollTimer.Stop()

    var lastPID int
    var lastBundle string
    lastState := ReadinessState("")
    var lastBundleErr string  // 연속 에러 suppression

    for {
        select {
        case <-ctx.Done():
            return nil
        case <-pollTimer.C:
        }

        pid, err := lookupPIDWithTimeout(ctx, cfg, pidFinder)
        if err != nil {
            log.Printf("pid lookup failed for device=%s: %v", cfg.DeviceID, err)
            pollTimer.Reset(retryDelay)
            continue
        }

        bundle, bundleErr := source.Bundle()
        if bundleErr != nil {
            // 연속 동일 에러 suppression
            if msg := bundleErr.Error(); msg != lastBundleErr {
                log.Printf("bundle read failed for %s: %v", source, bundleErr)
                lastBundleErr = msg
            }
            pollTimer.Reset(retryDelay)
            continue
        }
        lastBundleErr = ""

        if runErr := reconciler.Reconcile(pid, bundle); runErr != nil {
            log.Printf("reconcile failed for pid=%d %s: %v", pid, source, runErr)
            pollTimer.Reset(retryDelay)
            continue
        }

        // 상태 전이 로그 (기존과 동일)
        if pid != lastPID {
            log.Printf("attached pid=%d %s", pid, source)
            lastPID = pid
            lastBundle = bundle
        } else if bundle != lastBundle {
            log.Printf("reloaded bundle for pid=%d %s", pid, source)
            lastBundle = bundle
        }

        state := stateFunc()
        if state != lastState {
            if lastState == "" {
                log.Printf("state=%s pid=%d", state, pid)
            } else {
                log.Printf("state %s -> %s pid=%d", lastState, state, pid)
            }
            lastState = state
        }

        pollTimer.Reset(nextPollInterval(state, cfg.PidPollIntervalSeconds))
    }
}
```

`runWithSourceVersioner` 내부 함수 제거. `Run()`이 직접 loop를 실행.

### Runner — 변경 없음

`lifecycle/runner.go`의 FNV-1a digest 비교는 그대로 유지.
- Static (embedded): digest 항상 동일 → 사실상 PID 비교만 수행
- Override (file): 파일 변경 시 digest 변경 → 자동 reload

### 제거 대상

| 파일/패키지 | 라인 수 | 이유 |
|-------------|---------|------|
| `internal/agentbuild/` (5 files) | ~220 | BundleSource로 대체 |
| `internal/app/source_versioner.go` | ~223 | embed로 불필요 |
| `internal/app/source_versioner_test.go` | ~107 | 위와 동일 |
| `bundleCache` struct + `get()` in `run.go` | ~30 | BundleSource로 대체 |
| `BundleBuilder` interface in `run.go` | ~3 | BundleSource로 대체 |
| `runWithSourceVersioner()` in `run.go` | ~70 | Run()으로 통합 |
| `go.mod`: `fsnotify` | - | sourceVersioner 제거 |
| **합계** | **~550+** | |

### Makefile

```makefile
BUNDLE_DIR := internal/agentbundle/bundles

.PHONY: sync-bundles
sync-bundles:
	@test -d ../agent/generated || (echo "ERROR: agent generated/ not found" && exit 1)
	@ls ../agent/generated/*.js >/dev/null 2>&1 || (echo "ERROR: no .js bundles" && exit 1)
	rm -f $(BUNDLE_DIR)/*.js
	cp ../agent/generated/*.js $(BUNDLE_DIR)/

.PHONY: build
build: lint sync-bundles
	GOWORK=off $(GO) build ./...
```

- `sync-bundles`는 **개발 편의 도구**. `go build`는 커밋된 JS로 항상 성공.
- stale 파일 방지를 위해 복사 전 `rm -f`.
- source 부재 시 명시적 실패.

### 배포 변경

systemd unit 플래그 변경:
```diff
- --agent generated/thread-image-graft.js
+ --agent thread-image-graft
```

### Git 관리

- `frida/daemon/internal/agentbundle/bundles/*.js` — repo에 커밋
- `.gitattributes`에 `frida/daemon/internal/agentbundle/bundles/*.js binary` 추가하여 텍스트 diff 방지
- bundle 변경 빈도가 주 단위 이상이면 Git LFS 전환 검토

## 테스트 전략

### 제거 대상 테스트

- `source_versioner_test.go` 전체
- `run_test.go`의 `bundleCache` 관련 테스트 3개:
  - `TestBundleCacheSuppressesRebuildForCachedError`
  - `TestBundleCacheRetryBuildAfterSourceChange`
  - `TestBundleCacheDoesNotReturnStaleBundleAfterBuildError`
- `run_test.go`의 `TestRunRebuildsBundleAfterAgentSourceChanges`
- `run_test.go`의 `fakeBundleBuilder`, `fakeSourceVersioner`

### 수정 대상 테스트

- `run_test.go`: `fakeBundleBuilder` → `fakeBundleSource` (Bundle() + String())
- `config_test.go`: `AgentProjectRoot`/`AgentEntryPoint` → `AgentName`/`AgentOverride`
- `RunOnce` 제거 시 관련 테스트도 제거

### 신규 테스트

- `agentbundle/bundle_test.go`:
  - known bundle name → 정상 반환
  - unknown bundle name → 에러 + Available() 포함
  - `Available()` 정렬/안정성
  - `String()` 형식 검증
- `app/bundle_source_test.go`:
  - `FileBundleSource`: 정상 읽기, 파일 없음 에러, `String()` 형식
- `run_test.go` 신규:
  - override 파일 변경 시 다음 poll에서 새 bundle 적용
  - 연속 동일 에러 시 로그 suppression
- `config_test.go`:
  - `--agent` + `--agent-override` 파싱
  - `--agent-override` 단독 사용

## 의존성 방향

```
cmd/graft-daemon/main.go
    ├── internal/agentbundle  (embed, Source())
    ├── internal/app          (Run, Config, BundleSource interface, FileBundleSource)
    ├── internal/adb          (PIDFinder)
    ├── internal/fridaapi     (Runtime)
    └── internal/lifecycle    (Runner)

agentbundle → (없음. app을 import하지 않음)
app → fridaapi (HealthMessage 등)
lifecycle → fridaapi
```

`agentbundle`은 leaf 패키지. `app.BundleSource` 인터페이스를 import하지 않고 implicit satisfaction으로 충족. `main.go`에서 `var source app.BundleSource = s` 대입으로 컴파일 타임 검증.
