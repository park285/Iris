# README / 운영 문서 수정안

이 문서는 코드 수정과 함께 반드시 반영해야 하는 문서 문구를 모은 것입니다. 코드만 고치고 문서가 기존 계약을 계속 말하면 운영자가 잘못된 설정을 하게 됩니다.

## 현재 반영 상태

기준 시점: `2026-04-23` 현재 워크트리

- `1. Bridge 모듈 설명 수정안`: 반영됨. 루트 `README.md`에 `IRIS_REQUIRE_BRIDGE` auto/override 계약과 bridge optional/readiness 설명이 들어갔습니다.
- `2. Health check 문서 수정안`: 반영됨. 루트 `README.md`의 `/ready` 설명이 현재 bridge 판정 기준과 맞춰졌습니다.
- `3. 환경변수 표 수정안`: 부분 반영. `IRIS_REQUIRE_BRIDGE` 행은 반영되었지만 이 문서에 있는 추가 운영 변수 전체를 루트 README에 모두 옮기지는 않았습니다.
- `4. Webhook endpoint 정책 문서화`: 이번 동기화 범위 밖입니다.
- `5. 설정 변경 API 원자성 문서화`: 미반영. 구현은 현재 워크트리에 있으나 루트 README 문구 동기화는 남아 있습니다.
- `6. 설정 파일 validation 정책 문서화`: 미반영. 구현은 반영되었지만 운영 문구 정리는 남아 있습니다.
- `7. Secret 전달 방식 운영 문서`: 미반영. shell quoting hardening 구현은 반영되었지만 운영 문구 동기화는 남아 있습니다.
- `8. SSE event delivery 문서`: 미반영. SSE 안정성 구현은 반영되었지만 README/운영 문서 문구 동기화는 남아 있습니다.
- `9. Shutdown behavior` 이후 항목: 이번 동기화 범위 밖입니다.

## 1. Bridge 모듈 설명 수정안

### 권장 문구

```md
## Bridge 모듈

Iris의 bridge 모듈은 이미지 전송, 마크다운 전송 등 확장 메시지 전송 기능에 사용됩니다.

텍스트 웹훅만 사용하는 경우 bridge 없이도 Iris를 실행할 수 있습니다. 이 경우 `IRIS_REQUIRE_BRIDGE`를 설정하지 않거나 `false`로 둡니다.

```bash
IRIS_REQUIRE_BRIDGE=false
```

bridge 기능을 반드시 사용해야 하는 환경에서는 다음 값을 설정합니다.

```bash
IRIS_REQUIRE_BRIDGE=true
IRIS_BRIDGE_TOKEN=...
```

`IRIS_REQUIRE_BRIDGE=true`일 때는 bridge token이 없거나 bridge health check가 실패하면 `/ready`가 `503 Service Unavailable`을 반환합니다.

`IRIS_REQUIRE_BRIDGE=false`일 때는 bridge가 설치되어 있지 않아도 텍스트 웹훅 기능이 정상 설정되어 있으면 `/ready`가 성공할 수 있습니다.
```

### 기존 문장 교체 방향

수정 전:

```md
Bridge 모듈은 이미지 전송용이며 선택 사항입니다.
```

수정 후:

```md
Bridge 모듈은 이미지/확장 메시지 전송에 필요합니다. 텍스트 웹훅만 사용하는 환경에서는 선택 사항이며, `IRIS_REQUIRE_BRIDGE` 설정에 따라 readiness 검사에 포함됩니다.
```

## 2. Health check 문서 수정안

```md
## Health check

Iris는 다음 health endpoint를 제공합니다.

### `GET /health`

프로세스가 실행 중인지 확인하는 기본 health endpoint입니다. 설정이 완전히 끝나지 않았더라도 프로세스가 응답 가능하면 성공할 수 있습니다.

### `GET /ready`

Iris가 실제 요청을 처리할 준비가 되었는지 확인하는 readiness endpoint입니다.

`/ready`는 다음 조건을 확인합니다.

- inbound signing secret 설정 여부
- outbound webhook token 설정 여부
- bot control token 설정 여부
- webhook endpoint 설정 여부
- `IRIS_REQUIRE_BRIDGE=true`인 경우 bridge token 및 bridge health 상태

운영 환경에서는 `/ready`가 실패하더라도 HTTP 응답에는 자세한 내부 설정 정보가 노출되지 않을 수 있습니다. 자세한 실패 사유는 서버 로그에서 확인합니다.
```

외부 bind 경고도 함께 추가합니다.

```md
주의: `IRIS_BIND_HOST=0.0.0.0`으로 설정하면 health endpoint가 외부 네트워크에서 접근 가능할 수 있습니다. 운영 환경에서는 방화벽, 프록시 접근 제어 또는 내부 네트워크 바인딩을 사용하세요.
```

## 3. 환경변수 표 수정안

```md
## 환경변수

| 이름 | 기본값 | 설명 |
|---|---:|---|
| `IRIS_CONFIG_PATH` | `/data/local/tmp/iris_config.json` | Iris 설정 파일 경로 |
| `IRIS_BIND_HOST` | `127.0.0.1` | HTTP 서버 bind host |
| `IRIS_REQUIRE_BRIDGE` | `false` | bridge를 readiness 필수 조건으로 볼지 여부 |
| `IRIS_BRIDGE_TOKEN` | 없음 | bridge 호출에 사용하는 token |
| `IRIS_WEBHOOK_TOKEN` | 없음 | outbound webhook 인증 token |
| `IRIS_BOT_TOKEN` | 없음 | bot control API 인증 token |
| `IRIS_READY_VERBOSE` | `false` | `/ready` 실패 사유를 HTTP 응답에 자세히 노출할지 여부. 개발용으로만 권장 |
| `IRIS_ALLOW_CLEARTEXT_HTTP` | `false` | cleartext HTTP webhook 허용 여부 |
| `IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE` | `strict` | webhook transport 보안 정책 |
```

`IRIS_READY_VERBOSE`는 실제 코드에 추가하는 경우에만 문서화합니다.

## 4. Webhook endpoint 정책 문서화

route별 endpoint를 허용하는 방향이라면 아래 문구를 사용합니다.

```md
## Webhook endpoint 설정

Iris는 기본 webhook endpoint와 route별 webhook endpoint를 지원합니다.

기본 endpoint는 route별 endpoint가 없을 때 fallback으로 사용됩니다.

```json
{
  "endpoint": "https://example.com/iris/default",
  "webhooks": {
    "default": "https://example.com/iris/default",
    "image": "https://example.com/iris/image",
    "command": "https://example.com/iris/command"
  }
}
```

특정 route에 endpoint가 설정되어 있지 않고 기본 endpoint도 없으면, 해당 route의 webhook delivery는 전송되지 않고 reject 처리됩니다.

route별 endpoint만 사용하는 구성도 가능합니다. 이 경우 해당 route에 대해서만 delivery가 전송됩니다.
```

default endpoint를 필수로 유지하는 방향이라면 아래 문구를 사용합니다.

```md
## Webhook endpoint 설정

Iris는 기본 webhook endpoint를 필수 설정으로 사용합니다.

route별 endpoint는 특정 route의 전송 대상을 덮어쓰기 위해 사용합니다. route별 endpoint가 없는 경우 기본 endpoint로 전송됩니다.

기본 endpoint가 없으면 Iris는 webhook dispatcher를 ready 상태로 보지 않습니다.
```

두 문구를 동시에 넣으면 안 됩니다. 코드 정책과 같은 방향 하나만 선택해야 합니다.

## 5. 설정 변경 API 원자성 문서화

```md
## 설정 변경 API의 적용 방식

Iris의 설정 변경 API는 다음 순서로 동작합니다.

1. 요청 값 검증
2. 변경 후보 설정 생성
3. 설정 파일 저장
4. 런타임 설정 반영

설정 파일 저장에 실패하면 런타임 설정은 변경되지 않습니다. 즉, API가 실패 응답을 반환한 경우 현재 실행 중인 Iris의 effective config는 기존 값으로 유지됩니다.

일부 설정은 즉시 적용되며, 일부 설정은 재시작 후 적용됩니다. API 응답의 `applied`와 `requiresRestart` 값을 통해 적용 방식을 확인할 수 있습니다.
```

응답 예시:

```json
{
  "name": "dbrate",
  "persisted": true,
  "applied": true,
  "requiresRestart": false
}
```

```json
{
  "name": "botport",
  "persisted": true,
  "applied": false,
  "requiresRestart": true
}
```

## 6. 설정 파일 validation 정책 문서화

```md
## 설정 파일 검증

Iris는 설정 파일을 로드할 때 다음 항목을 검증합니다.

- HTTP port 범위
- DB polling rate 범위
- message send rate 범위
- jitter 범위
- webhook endpoint URL 형식
- webhook route 이름 형식
- command route prefix 형식
- image message route 형식
- secret/token 값의 앞뒤 공백 및 제어문자 포함 여부

잘못된 설정 파일이 감지되면 Iris는 해당 설정을 적용하지 않고 오류를 기록합니다.

route 이름은 영문자, 숫자, `-`, `_`만 사용할 수 있습니다.
```

```md
운영 환경에서는 HTTPS webhook endpoint 사용을 권장합니다. cleartext HTTP endpoint를 사용하려면 명시적으로 `IRIS_ALLOW_CLEARTEXT_HTTP=true`를 설정해야 합니다.
```

## 7. Secret 전달 방식 운영 문서

```md
## Secret 전달 방식

Iris 실행 스크립트는 token과 secret 값을 shell command 문자열에 직접 삽입하지 않습니다.

원격 디바이스에서 실행할 때는 다음 원칙을 지킵니다.

- 모든 경로는 shell-safe quoting을 적용합니다.
- 모든 token/secret 값은 shell-safe quoting을 적용합니다.
- 가능하면 token/secret은 명령줄 인자가 아니라 권한이 제한된 env 파일로 전달합니다.
- env 파일은 `0600` 권한으로 생성합니다.
- 로그에는 token/secret 원문을 출력하지 않습니다.

경로 또는 token 값에 공백, 작은따옴표, 달러 기호, 백틱이 포함되어도 추가 shell 명령이 실행되어서는 안 됩니다.
```

## 8. SSE event delivery 문서

```md
## SSE event delivery

Iris의 SSE endpoint는 `Last-Event-ID` 기반 재연결을 지원합니다.

클라이언트가 연결을 끊은 뒤 재연결할 때 `Last-Event-ID`를 전달하면, Iris는 해당 id 이후의 이벤트를 replay합니다.

서버는 subscriber 등록과 replay 계산을 하나의 원자적 단계로 처리하여 재연결 중 발생한 이벤트가 유실되지 않도록 보장합니다.

클라이언트는 동일한 event id를 중복 수신할 가능성에 대비해 id 기준 중복 제거를 수행해야 합니다.
```

```md
SSE payload가 여러 줄인 경우 서버는 각 줄마다 `data:` prefix를 붙여 전송합니다. 클라이언트는 여러 `data:` line을 `\n`으로 연결해 하나의 payload로 복원해야 합니다.
```

## 9. Shutdown behavior 문서

```md
## Shutdown behavior

Iris는 종료 시 다음 순서로 자원을 정리합니다.

1. ingress service 종료
2. dispatch loop 종료 대기
3. webhook outbox dispatcher 종료
4. reply admission worker 종료
5. config 저장
6. checkpoint journal flush
7. database close

종료 과정에서 이미 처리 완료된 command checkpoint는 flush되어야 합니다. 강제 종료나 프로세스 kill이 발생하면 마지막 checkpoint가 저장되지 않을 수 있으므로, 운영 환경에서는 graceful shutdown을 사용하세요.
```

## 10. Auth signing contract 문서

```md
# Iris request signing contract

Iris의 인증 서명은 다음 값을 기준으로 생성합니다.

```text
METHOD
CANONICAL_TARGET
TIMESTAMP
NONCE
BODY_SHA256_HEX
```

`CANONICAL_TARGET`은 path와 정규화된 query string으로 구성됩니다.

query parameter 정규화 규칙:

1. key와 value를 URL parameter encoding한다.
2. encoded key 기준 오름차순 정렬한다.
3. key가 같으면 encoded value 기준 오름차순 정렬한다.
4. `key=value` 형식으로 연결한다.
5. 여러 parameter는 `&`로 연결한다.
```

예시:

```text
path: /rooms/1/stats
query:
  period = 최근 7일
  filter = a&b=c

canonical target:
/rooms/1/stats?filter=a%26b%3Dc&period=%EC%B5%9C%EA%B7%BC%207%EC%9D%BC
```
