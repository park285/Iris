# 2026-03-31 reviewer guide

이 문서는 **현재 작업 트리 기준 최신 상태만** 설명하는 reviewer guide다.
이번 reviewer pack에서는 2026-03-30 문서나 기존 code-focus 문서를 기준으로 보지 않는다.

## reviewer가 먼저 확인할 것

1. `review-2.txt` 기준으로 남아 있던 구조 항목이 실제 코드로 닫혔는가
2. 그 변경이 테스트와 전체 품질 게이트로 다시 확인됐는가
3. 이번 pack이 과거 설명 문서가 아니라 최신 코드와 최신 문서만 담고 있는가

## 먼저 볼 문서

- [2026-03-31-reviewer-change-rationale.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-change-rationale.md)
- [2026-03-31-final-closure.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-final-closure.md)
- [2026-03-31-test-results.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-test-results.md)
- [2026-03-31-reviewer-bundle-manifest.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-reviewer-bundle-manifest.md)

## 먼저 볼 코드

### config path / bridge bootstrap

- [AGENTS.md](/home/kapu/gemini/Iris/AGENTS.md)
- [README.md](/home/kapu/gemini/Iris/README.md)
- [agent-reference.md](/home/kapu/gemini/Iris/docs/agent-reference.md)
- [IrisRuntimePathPolicy.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/IrisRuntimePathPolicy.kt)
- [BridgeRuntimeConfig.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/BridgeRuntimeConfig.kt)
- [BridgeRuntimeConfigTest.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/test/kotlin/party/qwer/iris/BridgeRuntimeConfigTest.kt)
- [IrisRuntimePathPolicyTest.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/test/kotlin/party/qwer/iris/IrisRuntimePathPolicyTest.kt)

### auth / protected body

- [RequestAuthenticator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RequestAuthenticator.kt)
- [AuthSupport.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/AuthSupport.kt)
- [ProtectedBodyAuth.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ProtectedBodyAuth.kt)
- [QueryRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/QueryRoutes.kt)
- [ConfigRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ConfigRoutes.kt)
- [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt)
- [RequestAuthenticatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/RequestAuthenticatorTest.kt)
- [AuthSupportTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/AuthSupportTest.kt)
- [QueryRoutesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/QueryRoutesTest.kt)
- [ConfigRoutesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/ConfigRoutesTest.kt)
- [ReplyRoutesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/ReplyRoutesTest.kt)

### image contract / verified handle core / staged future path

- [MessageSender.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSender.kt)
- [MessageSenderAdapters.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSenderAdapters.kt)
- [ImagePayloadHandle.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadHandle.kt)
- [Base64ImageIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/Base64ImageIngressService.kt)
- [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt)
- [MediaPreparationService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/MediaPreparationService.kt)
- [ReplyService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ReplyService.kt)
- [VerifiedImagePayloadHandleTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/VerifiedImagePayloadHandleTest.kt)
- [MessageSenderBytesAdapterTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MessageSenderBytesAdapterTest.kt)
- [Base64ImageIngressServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/Base64ImageIngressServiceTest.kt)
- [MediaPreparationServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/MediaPreparationServiceTest.kt)
- [ReplyRoutesMultipartTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt)

### snapshot / ingress / typed boundary

- [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt)
- [SnapshotMissingPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotMissingPolicy.kt)
- [SnapshotStateStore.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/SnapshotStateStore.kt)
- [SnapshotObserver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/SnapshotObserver.kt)
- [CommandIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt)
- [PeriodSpec.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/PeriodSpec.kt)
- [PeriodSpecParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/PeriodSpecParser.kt)
- [ThreadOriginMetadata.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/ThreadOriginMetadata.kt)
- [ThreadOriginMetadataDecoder.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ThreadOriginMetadataDecoder.kt)
- [JsonIdArrayParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/JsonIdArrayParser.kt)
- [RoomMetaParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RoomMetaParser.kt)
- [NonOpenRoomNameResolver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/NonOpenRoomNameResolver.kt)
- [RoomStatisticsService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RoomStatisticsService.kt)
- [ThreadListingService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ThreadListingService.kt)
- [MemberRepositoryMetadata.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepositoryMetadata.kt)
- [MemberRepository.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepository.kt)
- [CommandIngressServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ingress/CommandIngressServiceTest.kt)
- [SnapshotCoordinatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/snapshot/SnapshotCoordinatorTest.kt)
- [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt)
- [ObserverHelperLogicTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt)
- [ObserverHelperSnapshotTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperSnapshotTest.kt)
- [MemberRepositoryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt)
- [ThreadQueriesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ThreadQueriesTest.kt)

### reply worker lifecycle / delivery policy

- [ReplyAdmissionService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt)
- [WebhookDeliveryPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookDeliveryPolicy.kt)
- [WebhookOutboxDispatcher.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt)
- [ReplyAdmissionServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyAdmissionServiceTest.kt)
- [ReplyServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ReplyServiceTest.kt)
- [WebhookOutboxDispatcherTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt)
- [WebhookOutboxTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxTest.kt)

## reviewer가 확인해야 할 핵심 불변식

- 기본 runtime config path는 `/data/iris/config.json`이고 `IRIS_DATA_DIR`가 base override다
- bridge token은 config 우선, env fallback, none 상태를 source와 함께 구분한다
- 보호된 POST는 header precheck를 먼저 하고, body hash가 맞을 때만 nonce를 commit한다
- `MessageSender` core는 suspend + verified image handle만 받는다
- bytes convenience는 adapter로만 남고 unknown binary를 image로 승격하지 않는다
- multipart image ingress는 manifest-first, digest/length/content-type/known-image format을 강제한다
- future base64 ingress가 필요해져도 spill-backed verified handle로 즉시 내려간다
- `MediaPreparationService`는 verified image handle만 materialize한다
- snapshot은 `Present / MissingPending / MissingConfirmed`를 갖고, first miss는 무이벤트, pending restore는 quiet, confirmed restore만 emit한다
- ingress는 `lastPolledLogId`, `lastBufferedLogId`, `lastCommittedLogId`를 분리해 progress를 드러낸다
- reply admission worker는 mailbox state와 queue depth/age를 debug snapshot에 드러낸다
- outbox retry policy는 legacy dispatcher 상수가 아니라 `WebhookDeliveryPolicy`에서만 온다
- checkpoint는 여전히 ordered prefix만 전진한다
- 이번 pack은 최신 문서만 포함하고 이전 reviewer 문서는 기준으로 사용하지 않는다

## 검증 기준

이번 라운드에서 reviewer pack 기준으로 다시 실행한 최종 검증은 아래다.

```bash
./scripts/verify-all.sh
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest
```

추가로 reviewer가 바로 의심할 만한 축은 focused test로도 별도 확인했다.

```bash
./gradlew --no-daemon -Dkotlin.compiler.execution.strategy=in-process :app:testDebugUnitTest \
  --tests "party.qwer.iris.RequestAuthenticatorTest" \
  --tests "party.qwer.iris.http.AuthSupportTest" \
  --tests "party.qwer.iris.snapshot.SnapshotCoordinatorTest" \
  --tests "party.qwer.iris.reply.ReplyAdmissionServiceTest" \
  --tests "party.qwer.iris.delivery.webhook.WebhookOutboxDispatcherTest" \
  --tests "party.qwer.iris.http.Base64ImageIngressServiceTest" \
  --tests "party.qwer.iris.MemberRepositoryTest" \
  --tests "party.qwer.iris.ThreadQueriesTest"
```

세부 결과와 수치는 [2026-03-31-test-results.md](/home/kapu/gemini/Iris/docs/review/2026-03-31-test-results.md)를 본다.
