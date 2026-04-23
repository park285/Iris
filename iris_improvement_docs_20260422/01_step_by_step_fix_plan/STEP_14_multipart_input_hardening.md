# STEP 14. multipart 입력 처리 강화

우선순위: **P1**

## 진행 상태

기준 시점: `2026-04-23` 현재 워크트리

- 상태: 완료
- 구현 근거: `app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt`
- 검증 근거: `app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt`, `./gradlew app:testDebugUnitTest --tests 'party.qwer.iris.http.ReplyRoutesMultipartTest'`

## 1. 목적

multipart reply collector가 알 수 없는 part를 조용히 무시합니다. 보안 경계에서는 모르는 입력을 명확히 거부해야 합니다.

## 2. 대상 파일

- `app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt`
- `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt`

## 3. 확인된 위치

- MultipartReplyCollector.kt:95 — acceptMetadata()
- MultipartReplyCollector.kt:118 — acceptImage()

## 4. 현재 문제

metadata가 아닌 form item과 image가 아닌 file item이 조용히 무시됩니다. 클라이언트 실수나 공격성 입력이 발견되지 않습니다.

## 5. 수정 방향

허용되는 part 이름을 `metadata`, `image`로 제한하고 나머지는 400으로 거부합니다. metadata 크기 제한은 image 크기 제한과 분리합니다.

## 6. 구현 절차

- [ ] unknown form/file part 거부
- [ ] metadata 중복 정책 확정
- [ ] image 중복 정책 확정
- [ ] formFieldLimit metadata 기준 적용 가능성 확인

## 7. 코드 레벨 변경안

```kotlin
when (part) {
    is PartData.FormItem -> {
        if (part.name != "metadata") invalidRequest("unsupported multipart form part: ${part.name}")
        acceptMetadata(part, multipart)
    }
    is PartData.FileItem -> {
        if (part.name != "image") invalidRequest("unsupported multipart file part: ${part.name}")
        acceptImage(part)
    }
    else -> invalidRequest("unsupported multipart part")
}
```

## 8. 테스트 계획

- [ ] unknown form part 400 테스트
- [ ] unknown file part 400 테스트
- [ ] metadata 초과 413 테스트
- [ ] 중복 metadata 정책 테스트

## 9. 문서화 반영

API 문서에 허용 multipart 필드, 크기 제한, 중복 필드 정책을 적습니다.

## 10. 완료 기준

- 모르는 multipart field가 조용히 무시되지 않는다.
- metadata와 image 크기 제한이 분리된다.

## 11. 주의할 리스크

- Ktor formFieldLimit가 file part에도 영향을 주는지 확인해야 합니다.
