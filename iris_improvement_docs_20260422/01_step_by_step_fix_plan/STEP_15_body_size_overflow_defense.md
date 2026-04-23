# STEP 15. 요청 body size 계산 overflow 방어

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt`, `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/RequestBodyReaderTest.kt`, `app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt`

## 1. 목적

body byte count가 Int이고 `current + partBytes` 직접 덧셈을 사용합니다. 대용량/악성 입력에 대비해 Long과 overflow-safe check를 적용해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt`
- `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt`

## 3. 확인된 위치

- RequestBodyReader.kt:45 — var totalRead = 0
- ReplyRoutes.kt:169 — accumulateReplyBodyBytes()

## 4. 현재 문제

`totalRead`가 Int이고 body size 덧셈이 overflow-safe 하지 않습니다. low-level reader는 나중에 대용량 경로에 재사용될 수 있습니다.

## 5. 수정 방향

크기 계산은 Long으로 통일합니다. 덧셈 전 `partBytes > maxBytes - current` 방식으로 초과 여부를 검사합니다.

## 6. 구현 절차

- [ ] totalRead Long 변경
- [ ] declaredContentLength < 0 검증
- [ ] 덧셈 전 overflow-safe check 적용
- [ ] streaming digest와 input stream 경로 모두 수정

## 7. 코드 레벨 변경안

```kotlin
if (partBytes < 0) requestRejected("invalid part size", HttpStatusCode.BadRequest)
if (current > maxBytes || partBytes > maxBytes - current) {
    requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
}
return current + partBytes
```

## 8. 테스트 계획

- [ ] max size 직전 성공 테스트
- [ ] max size 직후 413 테스트
- [ ] 음수 content length 400 테스트
- [ ] overflow성 입력 테스트

## 9. 문서화 반영

API 문서에 body size limit과 초과 응답 코드를 적습니다.

## 10. 완료 기준

- body size 계산에서 integer overflow가 발생하지 않는다.
- 초과 요청은 항상 413으로 거부된다.

## 11. 주의할 리스크

- 타입 변경으로 호출부 signature가 바뀌면 fixture 수정이 필요합니다.
