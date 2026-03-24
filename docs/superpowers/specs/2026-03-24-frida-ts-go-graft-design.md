# Iris Frida TS/Go Graft 전환 설계

> 작성일: 2026-03-24
> 범위: `frida/` 계층 big-bang 전환, TypeScript agent, Go daemon, Frida Go API attach

---

## 배경

현재 이미지 thread graft 경로는 다음 세 요소에 의존한다.

1. 앱이 `/data/local/tmp/iris-thread-hint.json` 에 thread hint를 기록한다.
2. Frida JavaScript agent가 KakaoTalk 내부 send object를 hook하여 `threadId`/`threadScope`를 주입한다.
3. Python daemon이 `adb` 와 `frida` CLI를 사용해 KakaoTalk PID를 추적하고 script를 attach한다.

현 구현은 기능적으로 동작하지만 다음 한계가 있다.

- Frida agent source가 JavaScript 단일 파일이라 로직 분리, 타입 검증, 단계별 디버깅이 어렵다.
- daemon이 외부 CLI 프로세스 orchestration에 머물러 있어 attach/session/script lifecycle을 세밀하게 제어하기 어렵다.
- 이후 문제 분석 시 `script 자체 문제`, `attach 문제`, `runtime injection 문제`를 구조적으로 분리하기 어렵다.

운영이 아직 굳지 않았고 기존 구현을 백업할 예정이므로, 점진 rollout보다 `frida/` 계층을 한 번에 새 구조로 교체하는 편이 더 단순하다.

---

## 목표

- `frida/` 계층의 정본을 `TypeScript agent + Go daemon` 으로 전환한다.
- attach 경로를 `Frida Go API` 기반으로 재구성한다.
- 문제 지점을 `compile`, `attach`, `script load`, `hook install`, `runtime inject` 단계로 분리 가능하게 만든다.
- 기존 앱 계약은 유지한다.

## 비목표

- 앱의 `/reply`·`/reply-markdown` public contract 변경
- thread hint JSON shape 변경
- KakaoTalk 내부 hook 대상 클래스/필드의 의미 변경
- observer/webhook/reply pipeline 전반 리팩토링

---

## 고정 계약

이번 전환에서 아래 계약은 그대로 유지한다.

### 1. Hint file 계약

- 경로: `/data/local/tmp/iris-thread-hint.json`
- producer: `ReplyService.writeThreadHint()`
- JSON shape:

```json
{
  "room": "12345",
  "threadId": "3803466729815130113",
  "threadScope": 2
}
```

제약:

- `threadId` 는 문자열로 유지한다.
- `threadScope >= 2` 인 경우에만 image thread graft hint를 기록한다.
- agent는 hint를 읽은 뒤 즉시 삭제한다.
- hint는 one-shot 소비 자산으로 취급한다.
- parse 실패, room mismatch, field write 실패가 발생해도 hint는 재사용하지 않는다.

### 1-1. 검증 경계

이번 전환의 acceptance boundary는 아래 둘로 고정한다.

- producer 경계: `ReplyService.writeThreadHint()`
- consumer 경계: Frida agent의 send-object mutation

즉 `/reply`, `/reply-markdown`, admission 정책, 앱 내부 reply validation 정합성은 이번 전환의 성공 판정 범위에 넣지 않는다.
앱 쪽 image thread admission과 README 사이의 불일치는 기존 상태로 간주하며 out of scope다.

### 2. KakaoTalk runtime hook 계약

초기 전환에서는 현재 확인된 hook 지점을 유지한다.

- 대상 클래스: `com.kakao.talk.manager.send.ChatSendingLogRequest$a`
- 대상 메서드: `u()` overloads
- 대상 필드:
  - `G`: attachment JSON holder
  - `Z`: scope
  - `V0`: threadId

이는 public API가 아니므로 런타임 확인과 상세 로그가 필수다.

### 3. 기능 계약

- image send 경로에서만 hint file 기반 주입을 수행한다.
- `callingPkg` 제거 로직을 유지한다.
- room mismatch 시 주입하지 않는다.
- 초기 전환의 성공 기준은 기존 `thread-image-graft.js` 와 동등한 동작이다.
- markdown graft는 marker 기반 주입 경로를 유지한다.
- 초기 전환의 성공 기준은 기존 `thread-markdown-graft.js` 와 동등한 동작이다.

---

## 선택한 접근

최종 구조는 `frida/` 계층 big-bang 전환으로 고정한다.

- agent source of truth: TypeScript
- daemon runtime: Go
- attach backend: Frida Go API
- 기존 JS/Python 구현: `legacy` 백업 자산

이 접근을 선택한 이유는 다음과 같다.

- 운영 관성이 낮아 dual path 유지 비용보다 한 번에 교체하는 비용이 더 낮다.
- TS agent와 Go API attach를 한 프로세스에서 묶으면 문제 분리가 더 쉬워진다.
- 기존 구현을 그대로 백업하므로 rollback 경로는 유지된다.
- 앱-에이전트 계약을 고정하면 blast radius를 `frida/` 내부로 제한할 수 있다.

---

## 디렉터리 구조

새 구조는 아래를 기준으로 한다.

```text
frida/
  legacy/
    thread-image-graft.js
    thread-markdown-graft.js
    graft-daemon.py

  agent/
    package.json
    package-lock.json
    tsconfig.json
    thread-image-graft.ts
    thread-markdown-graft.ts
    shared/
      kakao.ts
      hint.ts
      log.ts
      message.ts

  daemon/
    go.mod
    cmd/
      graft-daemon/
        main.go
    internal/
      app/
      adb/
      agentbuild/
      fridaapi/
      lifecycle/
      logx/
```

원칙:

- 새 JS source는 두지 않는다.
- JS는 `legacy` 백업본 또는 디버그용 generated bundle로만 존재한다.
- daemon이 단일 진입점이 되고, agent는 compile 대상 자산으로만 관리한다.
- TypeScript toolchain은 `frida/agent/` 아래에 고정한다.
- Go toolchain은 `frida/daemon/` 아래에 고정한다.
- repo root에는 이번 전환 때문에 새로운 build root를 만들지 않는다.

---

## Agent 설계

### source of truth

- `frida/agent/thread-image-graft.ts`
- `frida/agent/thread-markdown-graft.ts`

### 모듈 책임

#### `shared/hint.ts`

- hint file 읽기
- JSON parse/validation
- `threadId` 문자열 유지
- 읽기 성공/실패/삭제 결과 로그화

#### `shared/kakao.ts`

- KakaoTalk class/method/field 상수 보관
- reflection helper 제공
- hook 대상 식별 실패 시 명확한 오류 반환

#### `shared/log.ts`

- `send()` 기반 구조화 로그 helper
- 단계별 prefix 통일

#### `shared/message.ts`

- message type 판별
- room match 판별
- `callingPkg` 제거 helper

### image graft 동작

1. `Java.perform` 진입
2. hook 대상 class/method 확인
3. `u()` overload wrapping
4. `Photo` message만 처리
5. attachment에서 `callingPkg` 제거
6. hint file read
7. room match 확인
8. room match 성공 시에만 `Z`, `V0` reflection write
9. verify log 송신

중요:

- `callingPkg` 제거 순서는 legacy 구현과 동일하게 `Photo` 경로 진입 직후 수행한다.
- no-hint, parse failure, room mismatch에서도 `callingPkg` 제거 동작은 유지한다.

### markdown graft 동작

- 기존 `thread-markdown-graft.js` 를 TS로 옮긴다.
- 초기 목적은 parity 확보이며, 추가 기능 확장은 하지 않는다.
- image 경로와 공통되는 reflection/log helper는 shared 모듈을 재사용한다.
- marker 탐지, scope/threadId overwrite, verify log 순서를 legacy와 동일하게 유지한다.

---

## Agent build 설계

정본은 끝까지 TS로 유지한다.

TS project root는 `frida/agent/` 로 고정한다.

- manifest: `frida/agent/package.json`
- lockfile: `frida/agent/package-lock.json`
- config: `frida/agent/tsconfig.json`
- daemon이 compiler에 넘기는 `project_root` 도 `frida/agent/` 로 고정한다.
- `frida-java-bridge` 는 explicit dependency로 둔다.

빌드 흐름은 아래 둘 중 하나를 허용한다.

1. `frida-compile` 로 bundle 생성
2. Frida compiler API로 `.ts`를 직접 build

초기 구현은 daemon 내부에서 compiler API를 호출해 메모리 bundle string을 얻고, 이를 그대로 `CreateScript(...)`에 전달한다.

보조 기능:

- `--dump-bundle <path>` 옵션으로 debug bundle 저장 가능
- bundle dump는 디버깅 용도이며 source of truth가 아니다

이 구조의 목적은 오류 위치를 아래 단계로 분리하는 것이다.

- TS compile 실패
- bundle 생성 실패
- attach 실패
- script load 실패
- hook install 실패
- runtime injection 실패

---

## Go daemon 설계

daemon은 다음 5개 모듈로 나눈다.

### 1. `adb`

책임:

- device 선택
- `pidof com.kakao.talk` 호출
- timeout/retry 처리

원칙:

- 외부 의존은 `adb` 로 제한한다.
- PID lookup 실패는 contextual error로 올린다.

### 2. `agentbuild`

책임:

- TS agent compile
- bundle string 반환
- optional bundle dump

원칙:

- compile 오류와 runtime 오류를 반드시 분리한다.
- target agent path를 명시적으로 로그에 남긴다.

### 3. `fridaapi`

책임:

- device/session attach
- `CreateScript(bundle)`
- `Load()`
- message/event 수신
- detach/cleanup

원칙:

- attach/session/script lifecycle 오류를 세분화한다.
- structured log로 `attach`, `create_script`, `load`, `detach` 단계 구분

### 4. `lifecycle`

책임:

- KakaoTalk PID 변화 감시
- 재attach backoff
- signal 기반 graceful shutdown
- script/session cleanup 순서 보장

원칙:

- PID 변경과 script 종료를 같은 이벤트로 뭉개지 않는다.
- 종료 시 `script unload -> session detach -> process cleanup` 순서를 보장한다.

### 5. `app`

책임:

- config/flag 파싱
- 모듈 wiring
- 실행 loop orchestration

---

## Daemon 실행 흐름

```text
start
  -> parse flags
  -> select device
  -> compile TypeScript agent
  -> poll KakaoTalk PID
  -> attach to device/process
  -> create script from compiled bundle
  -> load script
  -> receive script messages
  -> on PID change or detach, cleanup and re-attach
  -> on shutdown, graceful detach
```

### 주요 플래그

- `--device`
- `--agent`
- `--attach-timeout`
- `--pid-poll-interval`
- `--retry-delay`
- `--dump-bundle`
- `--log-level`

초기 기본값은 현재 Python daemon의 동작과 크게 어긋나지 않게 둔다.

- PID poll interval: 30s
- attach retry delay: 5s

---

## 로그와 오류 처리

문제 분석 편의를 위해 로그를 아래 단계로 고정한다.

- `build`: TS compile / bundle 생성
- `adb`: device 선택 / pid lookup
- `attach`: attach 시작 / 성공 / 실패
- `script`: create/load/unload
- `hook`: hook 설치 결과
- `inject`: hint read / room mismatch / callingPkg 제거 / field write / verify

원칙:

- 사용자에게 보여줄 오류와 내부 진단 로그를 분리한다.
- contextual error를 기본으로 한다.
- field/class name mismatch는 숨기지 않고 그대로 기록한다.
- hint parse 실패와 hint file 부재는 다른 이벤트로 기록한다.

---

## 백업과 롤백

기존 구현은 `frida/legacy/` 아래로 이동해 그대로 보존한다.

- `thread-image-graft.js`
- `thread-markdown-graft.js`
- `graft-daemon.py`

운영 스위치 경계는 명시적으로 분리한다.

- 새 기본 entrypoint: Go binary 1개
- legacy rollback entrypoint: Python daemon 1개 + legacy JS agent path
- profile은 `image` 와 `markdown` 두 가지로 나눈다.
- 같은 실행에서 `modern` 과 `legacy` 를 섞어 쓰지 않는다.

즉 rollback은 "어떤 binary/script 조합을 실행할지"를 바꾸는 문제로 정의한다.

예시:

- modern: `graft-daemon --agent frida/agent/thread-image-graft.ts`
- modern markdown: `graft-daemon --agent frida/agent/thread-markdown-graft.ts`
- legacy: `python3 frida/legacy/graft-daemon.py --script frida/legacy/thread-image-graft.js`
- legacy markdown: `python3 frida/legacy/graft-daemon.py --script frida/legacy/thread-markdown-graft.js`

릴리스 경계:

- 활성 profile은 한 시점에 정확히 하나다.
- `modern-image`, `modern-markdown`, `legacy-image`, `legacy-markdown` 중 하나를 고른다.
- rollback 가능 판정은 legacy 명령이 실제로 1회 이상 실행되는지까지 포함한다.

롤백 원칙:

- 앱 쪽 hint file 계약은 유지되므로 rollback 범위는 `frida/` 내부로 제한된다.
- 새 Go daemon이 실패할 경우 legacy 조합으로 즉시 되돌릴 수 있다.
- generated bundle은 rollback 자산이 아니다.

---

## 검증 전략

검증은 3단계로 수행한다.

### 1. 정적 검증

- Go build 성공
- TS compile 성공
- hook 대상 class/method/field 상수 검토
- legacy JS와 새 TS의 기능 parity 점검
- TS project root와 toolchain 파일이 `frida/agent/` 내부에 고정되어 있는지 확인

### 2. 단위 검증

- hint JSON parse 테스트
- partial JSON parse 실패 테스트
- `threadId` 문자열 보존 테스트
- room mismatch 테스트
- room mismatch 후 hint 재사용 없음 테스트
- `callingPkg` 제거 helper 테스트
- no-hint, parse failure, room mismatch에서 `callingPkg` 제거가 유지되는지 테스트
- markdown graft marker/metadata helper 테스트
- PID parse / retry / shutdown 테스트
- cleanup order 테스트: `script unload -> session detach -> process cleanup`

### 3. 실동작 검증

- attach 성공
- script load 성공
- hook installed log 확인
- image send 시 hint file read/delete 확인
- room match 시 thread inject 확인
- room mismatch 시 no-op 확인
- parse failure 시 hint 재시도 없이 폐기되는지 확인
- markdown graft load 및 marker 기반 thread 주입 smoke 확인
- graceful shutdown 시 unload/detach 순서 로그 확인
- `legacy-image`, `legacy-markdown` rollback command를 각각 1회 이상 실행해 실제 진입점 생존 확인

성공 기준:

- 기존 image thread graft와 기능적으로 동등
- 기존 markdown graft와 기능적으로 동등
- attach/session 재시작 시 누수나 중복 attach 없음
- compile/attach/runtime 오류가 서로 다른 로그 단계에서 식별 가능
- modern/legacy 전환이 명령 단위로 재현 가능

---

## 리스크와 대응

### 1. KakaoTalk 내부 필드명 변경

리스크:

- `G`, `Z`, `V0` 같은 obfuscated field는 앱 업데이트 시 깨질 수 있다.

대응:

- reflection 실패를 상세 로그로 노출한다.
- shared 상수 모듈에 hook metadata를 모아 수정 지점을 최소화한다.

### 2. TS compile 성공 but runtime 실패

리스크:

- type safety가 runtime reflection 안정성을 보장하지 않는다.

대응:

- compile 단계와 hook install 단계를 로그로 분리한다.
- debug bundle dump를 지원해 runtime investigation을 돕는다.

### 3. attach lifecycle 회귀

리스크:

- Go API attach 경로에서 session cleanup이나 재attach 타이밍이 어긋날 수 있다.

대응:

- lifecycle 모듈을 분리한다.
- PID 변경, detach, shutdown 경로를 각각 테스트한다.

### 4. source/generated 혼동

리스크:

- generated JS를 정본처럼 수정하면 디버깅과 유지보수가 꼬인다.

대응:

- generated bundle은 전용 경로로 분리하고 기본 커밋 대상에서 제외한다.
- 정본은 TS라는 원칙을 문서와 디렉터리로 강제한다.

---

## 구현 원칙

- 최소 변경 범위는 `frida/` 로 제한한다.
- 앱 쪽 계약은 유지한다.
- 정본은 TS/Go만 둔다.
- runtime 불확실성은 타입이 아니라 로그와 단계 분리로 관리한다.
- big-bang 교체를 하더라도 rollback은 즉시 가능해야 한다.

---

## 완료 기준

다음 조건을 만족하면 전환을 완료로 본다.

1. legacy 자산이 `frida/legacy/` 에 안전하게 백업되어 있다.
2. TS agent가 compile되어 Go daemon을 통해 load된다.
3. Go daemon이 Frida Go API로 KakaoTalk에 attach한다.
4. image thread graft가 기존과 동등하게 동작한다.
5. markdown graft가 기존과 동등하게 동작한다.
6. `legacy-image` 와 `legacy-markdown` rollback entrypoint가 실제 실행 가능하다.
7. 주요 실패 지점이 로그 단계만 보고 구분 가능하다.
