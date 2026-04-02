# iris-common

`iris-ctl`과 `iris-daemon`이 공유하는 라이브러리 크레이트.
HMAC 인증, Iris HTTP API 클라이언트, 전송 협상, 응답 모델을 제공한다.

## 모듈

### `auth`

HMAC-SHA256 요청 서명 모듈.

```rust
use iris_common::auth::signed_headers;

// X-Iris-Timestamp, X-Iris-Nonce, X-Iris-Signature, X-Iris-Body-Sha256 4개 헤더 반환
let headers = signed_headers("GET", "/api/rooms", b"", "shared-token");
```

**정규 요청 형식** (서명 대상 5줄 문자열):

```
METHOD
/target/path
timestamp
nonce
body-sha256-hex
```

### `config`

연결 트레이트 및 구현체.

```rust
use iris_common::config::IrisConnection;

// 자체 설정 구조체에 구현:
impl IrisConnection for MyConfig {
    fn base_url(&self) -> &str { &self.url }
    fn token(&self) -> &str { &self.token }
}

// 또는 제공된 SimpleConnection으로 간단히 사용:
use iris_common::config::SimpleConnection;
let conn = SimpleConnection::new("http://localhost:3000", "token");
```

### `api`

Iris HTTP API 전체 클라이언트. `http://` URL에 대해 자동으로 H2C(HTTP/2 cleartext, prior-knowledge)를 사용한다.

```rust
use iris_common::api::IrisApi;

let api = IrisApi::new(&config);

// 사용 가능한 엔드포인트
api.health().await?;                              // 헬스 체크
api.ready().await?;                               // 준비 상태 확인
api.bridge_diagnostics().await?;                  // 브릿지 진단 정보
api.rooms().await?;                               // 방 목록 조회
api.members(chat_id).await?;                      // 방 멤버 조회
api.room_info(chat_id).await?;                    // 방 상세 정보
api.stats(chat_id).await?;                        // 방 통계
api.member_activity(chat_id, member_id).await?;   // 멤버 활동 내역
api.recent_messages(chat_id, limit).await?;       // 최근 메시지 조회
api.send_reply(request).await?;                   // 답장 전송
api.list_threads(chat_id).await?;                 // 스레드 목록
api.get_room_events(chat_id, cursor).await?;      // 방 이벤트 이력
api.sse_request(path, query).await?;              // SSE 스트림 (raw)
```

**전송 계층 오버라이드**: 소비자 설정에서 `transport = "http1"`을 지정하면 H2C 대신 HTTP/1.1을 강제한다.

### `models`

Serde 직렬화/역직렬화 가능한 응답 타입:

- `RoomSummary`, `MemberInfo`, `StatsResponse`
- `BridgeDiagnosticsResponse`
- `ReplyRequest`, `ReplyAcceptedResponse`
- `SseEvent`, `ThreadListResponse`, `RecentMessage`

## 사용법

`Cargo.toml`에 추가:

```toml
[dependencies]
iris-common = { path = "../iris-common" }
```
