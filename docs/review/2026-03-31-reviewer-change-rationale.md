# 2026-03-31 reviewer change rationale

이 문서는 외부 reviewer가 이번 최신 작업 트리를 볼 때, 단순히 "무엇이 바뀌었는가"가 아니라 "왜 그 변경이 필요했고 어떤 구조 리스크를 닫기 위한 것이었는가"를 빠르게 파악하도록 만든 최신-only 근거 문서다.

이 문서의 범위는 `review-2.txt` 기준으로 마지막까지 남아 있던 구조 항목들이다.
과거 2026-03-30 rationale 문서는 이번 pack의 기준이 아니다.

## 한 문장 요약

이번 라운드의 목적은 기능을 더 늘리는 것이 아니라, 이미 좋아진 경로를 **더 좁고 더 명시적인 계약**으로 고정해서 미래 회귀와 운영 혼선을 줄이는 것이었다.

## 1. runtime path 정책을 공용화한 이유

이전에는 app 쪽 기본 config path와 bridge 쪽 기본 config path가 완전히 같은 정책으로 보이지 않았다.
이 상태에서는 운영자가 같은 `config.json`을 편집한다고 생각해도, 실제로는 어느 런타임이 어떤 기본값을 따르는지 reasoning이 흔들릴 수 있다.

그래서 이번에는:

- [IrisRuntimePathPolicy.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/IrisRuntimePathPolicy.kt)
- [ConfigPathPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPathPolicy.kt)
- [BridgeRuntimeConfig.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/BridgeRuntimeConfig.kt)

를 통해 **공용 runtime path policy**를 기준으로 맞췄다.

reviewer가 봐야 할 핵심 이유는 단순하다.

- `IRIS_DATA_DIR` 하나로 runtime base를 설명할 수 있어야 한다
- app/bridge/imagebridge-protocol가 같은 기본 config path를 말해야 한다
- 운영 문서도 그 코드 계약을 그대로 따라야 한다

즉, 이 변경은 "경로 문자열 하나 고쳤다"가 아니라 **시스템 전체의 설정 진실 공급원**을 하나로 맞춘 것이다.

## 2. bridge token resolution을 typed result로 바꾼 이유

bridge token은 있으면 편한 값이 아니라 handshake trust의 핵심 값이다.
그런데 단순 `String` 반환만으로는 reviewer가 다음을 한 번에 알기 어렵다.

- config file에서 왔는가
- env fallback에서 왔는가
- 아무 값도 없었는가
- 어떤 config path를 기준으로 읽었는가

그래서 [BridgeRuntimeConfig.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/BridgeRuntimeConfig.kt) 에서 `BridgeTokenResolution(token, source, configPath)`로 바꿨다.

이 변경의 이유는 두 가지다.

- precedence를 구현이 아니라 타입으로 드러내기 위해
- 운영 로그와 reviewer reasoning에서 "왜 이 token이 선택됐는가"를 분명히 하기 위해

여기서 reviewer가 봐야 할 invariant는:

- source는 `CONFIG_FILE` / `ENV_FALLBACK` / `NONE` 셋 중 하나로 고정된다
- config file 값이 있으면 그것이 우선한다
- env는 fallback이며, blank면 선택되지 않는다

## 3. MessageSender를 send contract로만 남긴 이유

이전 구조에서는 core send contract와 blocking convenience, bytes convenience가 너무 가까웠다.
그 상태는 당장 동작에는 문제 없어도, 미래 구현자가 다시 "bytes도 사실상 core 경로인가?"처럼 읽기 쉬운 구조다.

그래서 이번에는:

- [MessageSender.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSender.kt)
- [MessageSenderAdapters.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSenderAdapters.kt)

로 나눠서 **core**와 **adapter/convenience**를 분리했다.

이 변경의 이유는 명확하다.

- core는 suspend + verified handle만 받는다
- blocking wrapper는 edge convenience일 뿐 core contract가 아니다
- bytes 경로도 직접 전송이 아니라 verified handle 승격을 거쳐야 한다

즉, reviewer는 이제 "무엇이 공식 전송 계약인가"를 파일 구조만 보고도 판단할 수 있다.

## 4. VerifiedImagePayloadHandle을 중심에 둔 이유

이번 라운드의 이미지 관련 핵심은 "그냥 바이트 손잡이"와 "검증된 이미지 손잡이"를 구분한 것이다.

- [ImagePayloadHandle.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadHandle.kt)
- [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt)
- [MediaPreparationService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/MediaPreparationService.kt)

에서 core로 들어가는 이미지는 `VerifiedImagePayloadHandle`이어야 한다.

이 구조가 필요한 이유는:

- multipart ingress에서만 엄격하고 내부 helper는 느슨한 상태를 없애기 위해
- unknown blob이 나중 단계에서야 실패하는 구조를 피하기 위해
- prepare 단계가 "이미지 판별"이 아니라 "검증된 이미지를 materialize"하는 단계가 되게 하기 위해

reviewer가 확인해야 할 핵심은 아래다.

- bytes convenience도 `verifyImagePayloadHandles()`를 거친다
- multipart image는 manifest-first + digest/length/content-type/known-image format을 본다
- `MediaPreparationService`는 verified format과 실제 header mismatch를 거절한다

즉, 이번 변경은 성능보다 **계약 강도**를 높인 것이다.

## 5. 보호된 POST를 body-before-auth에서 2단계 인증으로 바꾼 이유

이전 구조에서는 보호된 POST가 body를 먼저 읽고, 그 다음 서명을 확인했다.
`RequestBodyReader` 자체는 streaming digest/spill을 잘 하고 있었지만, 잘못된 서명 요청에도 먼저 IO/CPU 비용을 쓰는 구조였다.

그래서 이번에는:

- [RequestAuthenticator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RequestAuthenticator.kt)
- [AuthSupport.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/AuthSupport.kt)
- [ProtectedBodyAuth.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ProtectedBodyAuth.kt)

를 통해 `precheck -> body read -> finalize` 구조로 나눴다.

핵심 이유는 두 가지다.

- 잘못된 서명 요청은 body를 읽기 전에 바로 탈락시키기 위해
- nonce를 헤더 precheck에서 너무 일찍 소모하지 않기 위해

reviewer가 봐야 할 invariant는:

- POST precheck는 `X-Iris-Body-Sha256`를 포함한 header와 서명만 먼저 본다
- 실제 body hash가 맞을 때만 finalize 단계에서 nonce를 기록한다
- body hash mismatch는 nonce를 소모하지 않는다

## 6. SnapshotCoordinator를 pure actor + grace state로 바꾼 이유

이전 구조의 `SnapshotCoordinator`는 actor처럼 보였지만 실제로는 `Atomic*`와 concurrent set이 섞여 있었다.
또 missing 상태도 너무 거칠어서, snapshot source에서 잠깐 안 보인 것을 곧바로 synthetic leave/join으로 번역했다.

그래서 이번에는:

- [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt)
- [SnapshotMissingPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotMissingPolicy.kt)
- [SnapshotStateStore.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/SnapshotStateStore.kt)
- [SnapshotObserver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/SnapshotObserver.kt)

를 기준으로 single-writer actor와 `Present / MissingPending / MissingConfirmed` 전이를 맞췄다.

이 변경의 이유는:

- snapshot mutable state를 actor 내부만 소유하게 하기 위해
- first miss와 confirmed missing을 구분해서 room visibility glitch를 거칠게 event로 번역하지 않게 하기 위해
- prune를 confirmed missing만 대상으로 제한하기 위해

reviewer가 봐야 할 invariant는:

- first missing에서는 leave 이벤트가 없다
- pending restore는 quiet하다
- confirmed restore만 `diffRestored`를 탄다
- prune는 confirmed missing만 제거한다

## 7. CommandIngressService에 watermark snapshot을 넣은 이유

`CommandIngressService`는 이미 hot path offload와 ordered checkpoint 측면에서 많이 좋아진 상태였다.
이번에 남아 있던 문제는 "어디까지 왔는지 해석하기 어렵다"는 점에 더 가까웠다.

그래서 [CommandIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt) 에서 아래를 분리했다.

- `lastPolledLogId`
- `lastBufferedLogId`
- `lastCommittedLogId`
- `IngressProgressSnapshot`

이 변경의 이유는:

- DB에서 어디까지 읽었는지
- global buffer에 어디까지 올렸는지
- checkpoint가 어디까지 commit됐는지

를 reviewer와 운영자가 같은 언어로 볼 수 있게 하려는 것이다.

이건 behavior change라기보다 **observability와 reasoning의 정리**에 가깝다.
ordered checkpoint semantics는 그대로 유지되고, 대신 progress 해석이 더 분명해졌다.

## 8. typed boundary를 올린 이유

이번 라운드에서는 raw string/JSONObject를 service 안쪽에서 밀어내는 작업도 같이 했다.

- [PeriodSpec.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/PeriodSpec.kt)
- [PeriodSpecParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/PeriodSpecParser.kt)
- [ThreadOriginMetadata.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/ThreadOriginMetadata.kt)
- [ThreadOriginMetadataDecoder.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ThreadOriginMetadataDecoder.kt)
- [JsonIdArrayParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/JsonIdArrayParser.kt)
- [RoomMetaParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RoomMetaParser.kt)
- [NonOpenRoomNameResolver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/NonOpenRoomNameResolver.kt)

이 변경의 이유는 단순하다.

- period는 string `"7d"`가 아니라 type으로 읽혀야 한다
- thread origin metadata는 `JSONObject` 직접 파싱이 아니라 typed decode여야 한다
- metadata helper 한 파일에 파싱/정책/조합이 다 섞이지 않아야 한다

즉, 이번 라운드는 behavior뿐 아니라 `raw primitive/json 경계를 어디서 끊는가`도 같이 정리했다.

## 9. ReplyAdmissionService worker lifecycle을 드러낸 이유

이전의 `ReplyAdmissionService`는 command actor 쪽은 좋았지만, worker mailbox 상태는 `Channel + Job` 조합으로만 숨어 있었다.
closed worker 재시도도 재귀였다.

그래서 이번에는:

- [ReplyAdmissionService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt)

에서 worker mailbox를 `Open / Closing / Closed`로 명시했고, debug snapshot에 `queueDepth`, `ageMs`, `mailboxState`를 넣었다.

이 변경의 이유는:

- queue saturation과 worker churn을 운영 시점에 읽을 수 있게 하기 위해
- closed worker replacement를 재귀가 아니라 loop로 단순화하기 위해
- shutdown 시 pending drain과 hard cancel의 경계를 명확하게 하기 위해

## 10. outbox retry policy를 legacy에서 떼어낸 이유

이전에는 `WebhookOutboxDispatcher` 기본 retry 횟수가 legacy `H2cDispatcher` 상수에서 왔다.
현재 주 경로가 durable outbox인데, 기본 policy가 legacy best-effort 경로 상수를 보는 건 구조적으로 좋지 않았다.

그래서 이번에는:

- [WebhookDeliveryPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookDeliveryPolicy.kt)
- [WebhookOutboxDispatcher.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt)

로 retry/poll/claim recovery 기준을 현재 경로 내부 policy로 올렸다.

reviewer가 봐야 할 핵심은:

- outbox retry 기준은 더 이상 `H2cDispatcher.MAX_DELIVERY_ATTEMPTS`에 묶이지 않는다
- policy source는 `WebhookDeliveryPolicy` 하나다

## 11. base64 future path를 staged handle로 정리한 이유

현재 HTTP 이미지 ingress는 multipart + verified handle 기준이다.
다만 외부 제약 때문에 base64 경계가 다시 필요해질 수 있다.

그래서 이번에는:

- [Base64ImageIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/Base64ImageIngressService.kt)
- [ImagePayloadHandle.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadHandle.kt)

을 통해 base64가 다시 들어와도 즉시 decode하고, spill-backed `VerifiedImagePayloadHandle`로 내려 core를 다시 흔들지 않게 했다.

핵심 이유는:

- base64는 boundary format이어야지 core format이 되면 안 되기 때문이다
- 큰 payload도 메모리에 오래 남지 않게 하기 위해서다

## 12. reviewer pack을 최신-only로 유지한 이유

이번 reviewer pack은 과거 문서를 함께 넣으면 오히려 reviewer를 혼란스럽게 만든다.
그래서 최신 pack은 다음 문서만 기준으로 잡는다.

- [2026-03-31-reviewer-guide.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-guide.md)
- [2026-03-31-reviewer-change-rationale.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-change-rationale.md)
- [2026-03-31-final-closure.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-final-closure.md)
- [2026-03-31-test-results.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-test-results.md)
- [2026-03-31-reviewer-bundle-index.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-bundle-index.md)
- [2026-03-31-reviewer-bundle-manifest.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-bundle-manifest.md)

즉, reviewer는 historical discussion이 아니라 **현재 코드와 현재 근거**만 보면 된다.

## 13. 이번 reviewer가 최소한 신뢰해도 되는 것

[2026-03-31-test-results.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-test-results.md) 기준으로, 이번 최신 상태는 `./scripts/verify-all.sh` 전체를 다시 통과했다.

최소한 아래는 다시 확인됐다.

- app unit test: 100 suites / 635 tests / 0 fail
- bridge unit test: 16 suites / 66 tests / 0 fail
- imagebridge-protocol test: 2 suites / 8 tests / 0 fail
- Rust test: `iris-common` 15, `iris-ctl` 38, `iris-daemon` 45 passed
- Miri: `iris-common` 15, `iris-ctl` 36 passed + 2 ignored, `iris-daemon` 45 passed
- boundary / shell integration 통과

`cargo deny`는 duplicate / license-not-encountered warning이 있었지만 exit code는 성공이었다.

## 마무리

이번 rationale의 핵심은 하나다.

이번 변경은 "새 기능을 더 넣은 것"보다, 이미 괜찮아진 시스템을 **더 좁고 명시적인 계약으로 고정한 것**이다.

그래서 reviewer는 diff를 볼 때 아래 질문만 보면 된다.

- 이 변경이 동일한 config truth를 강제하는가
- 이 변경이 image를 verified contract로 밀어 올렸는가
- 이 변경이 ingress progress를 더 분명하게 만들었는가
- 이 pack이 정말 최신-only인가

이 네 질문에 대한 답이 코드와 테스트에서 모두 `yes`라면, 이번 라운드의 목적은 달성된 것이다.
