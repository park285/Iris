# STEP 07. SSE frame multiline 안정화

우선순위: **P0**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 구현 반영 완료, Kotlin/Gradle 검증 보류
- 구현 근거: `app/src/main/java/party/qwer/iris/http/SseEventEnvelope.kt`, `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`, `tools/iris-ctl/src/sse.rs`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/SseHeartbeatTest.kt`, `cargo test --manifest-path tools/iris-ctl/Cargo.toml sse` 통과
- 메모: server formatter와 Rust parser가 모두 multiline `data:` 규칙을 처리하는 형태로 정리되었습니다.

## 1. 목적

SSE payload에 줄바꿈이 들어가면 현재 frame 조립 방식이 표준 형식을 깨뜨릴 수 있습니다. 서버 formatter와 Rust client parser를 같이 고쳐야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/SseEventEnvelope.kt`
- `app/src/main/java/party/qwer/iris/http/MemberRoutes.kt`
- `tools/iris-ctl/src/sse.rs`

## 3. 확인된 위치

- SseEventEnvelope.kt:10 — initialSseFrames()
- MemberRoutes.kt:92 — live SSE writeStringUtf8
- tools/iris-ctl/src/sse.rs:41 — parse_message()

## 4. 현재 문제

현재 서버는 `data: ${payload}` 형태로 payload를 한 줄에 그대로 붙입니다. payload에 줄바꿈이 포함되면 다음 줄은 `data:` prefix 없이 들어가거나 payload 내부 문자열이 SSE field처럼 해석될 수 있습니다. Rust client도 `data:` 줄을 하나만 저장합니다.

## 5. 수정 방향

서버에는 `formatSseFrame()` 공통 함수를 만들고, payload를 줄 단위로 쪼개 각 줄마다 `data: `를 붙입니다. Rust parser는 여러 data line을 모아 `\n`으로 합쳐 JSON parse합니다.

## 6. 구현 절차

- [ ] `formatSseFrame()` 공통 함수 추가
- [ ] payload 줄바꿈을 `
- [ ] `으로 정규화
- [ ] 각 payload line마다 `data: ` prefix 적용
- [ ] event type에서 줄바꿈 제거
- [ ] Rust parser에서 `Vec<&str>`로 여러 data line 수집

## 7. 코드 레벨 변경안

```kotlin
internal fun formatSseFrame(event: SseEventEnvelope): String =
    buildString {
        append("id: ").append(event.id).append('\n')
        append("event: ").append(sanitizeSseField(event.eventType)).append('\n')

        event.payload
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { line ->
                append("data: ").append(line).append('\n')
            }

        append('\n')
    }
```

```rust
let mut data_lines: Vec<&str> = Vec::new();

if let Some(data) = line.strip_prefix("data: ") {
    data_lines.push(data);
}

let data = data_lines.join("\n");
serde_json::from_str::<SseEvent>(&data).ok()
```

## 8. 테스트 계획

- [ ] multiline payload frame 테스트
- [ ] event type 줄바꿈 sanitize 테스트
- [ ] Rust parser multiple data line 테스트
- [ ] 빈 data line 테스트

## 9. 문서화 반영

SSE 문서에 multiline payload 처리 규칙을 적습니다. 클라이언트는 여러 data line을 `\n`으로 연결해야 합니다.

## 10. 완료 기준

- 서버의 모든 SSE write 경로가 공통 formatter를 사용한다.
- payload 줄바꿈이 frame 구조를 깨지 않는다.
- Rust client가 여러 data line을 정상 복원한다.

## 11. 주의할 리스크

- server/client를 같이 고치지 않으면 호환성 문제가 생깁니다.
- JSON payload는 보통 한 줄이라 문제가 숨어 있을 수 있으니 테스트를 강제해야 합니다.
