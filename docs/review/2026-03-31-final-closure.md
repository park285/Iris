# 2026-03-31 final closure

이 문서는 현재 작업 트리 기준으로, `review-2.txt`를 정본으로 다시 보았을 때 남아 있던 핵심 구조 이슈가 어떻게 닫혔는지 정리한 최신 closure 문서다.

## 최종 판단

- 상태: `closed`
- 기준: 현재 코드 + 최신 문서 + `./scripts/verify-all.sh` 전체 성공
- reviewer 전달물: 최신 reviewer 문서 6개와 aligned bundle 1개

## 닫힌 항목

### 1. config path / runtime base contract

- 상태: `closed`
- 근거 코드:
  [AGENTS.md](/home/kapu/gemini/Iris/AGENTS.md),
  [README.md](/home/kapu/gemini/Iris/README.md),
  [agent-reference.md](/home/kapu/gemini/Iris/docs/agent-reference.md),
  [IrisRuntimePathPolicy.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/IrisRuntimePathPolicy.kt),
  [ConfigPathPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPathPolicy.kt)
- 최종 판단:
  기본 config path는 `/data/iris/config.json`으로 통일됐고, `IRIS_DATA_DIR`가 base override가 된다.

### 2. bridge bootstrap / token precedence clarity

- 상태: `closed`
- 근거 코드:
  [BridgeRuntimeConfig.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/main/kotlin/party/qwer/iris/BridgeRuntimeConfig.kt)
- 최종 판단:
  bridge token은 `CONFIG_FILE` / `ENV_FALLBACK` / `NONE`으로 source가 드러나는 `BridgeTokenResolution`으로 해석된다.
- 근거 테스트:
  [BridgeRuntimeConfigTest.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/test/kotlin/party/qwer/iris/BridgeRuntimeConfigTest.kt),
  [IrisRuntimePathPolicyTest.kt](/home/kapu/gemini/Iris/imagebridge-protocol/src/test/kotlin/party/qwer/iris/IrisRuntimePathPolicyTest.kt)

### 3. protected POST body-before-auth removal

- 상태: `closed`
- 근거 코드:
  [RequestAuthenticator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RequestAuthenticator.kt),
  [AuthSupport.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/AuthSupport.kt),
  [ProtectedBodyAuth.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ProtectedBodyAuth.kt),
  [QueryRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/QueryRoutes.kt),
  [ConfigRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ConfigRoutes.kt),
  [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt)
- 최종 판단:
  보호된 POST는 header precheck를 먼저 하고, body hash가 맞을 때만 nonce를 commit한다.
  invalid signature 요청은 body를 읽기 전에 탈락한다.

### 4. image handle-only core contract

- 상태: `closed`
- 근거 코드:
  [MessageSender.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSender.kt),
  [MessageSenderAdapters.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MessageSenderAdapters.kt),
  [ImagePayloadHandle.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadHandle.kt),
  [ReplyService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ReplyService.kt)
- 최종 판단:
  core contract는 suspend + verified image handle만 받는다.
  blocking/bytes convenience는 adapter로만 남는다.

### 5. known-image enforcement와 verified materialization

- 상태: `closed`
- 근거 코드:
  [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt),
  [ImagePayloadValidation.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadValidation.kt),
  [MediaPreparationService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/MediaPreparationService.kt),
  [ImageEncoder.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImageEncoder.kt)
- 최종 판단:
  multipart ingress는 manifest-first와 known-image format을 강제한다.
  bytes adapter도 unknown binary를 verified handle로 승격하지 않는다.
  prepare 단계는 verified image만 materialize한다.
- 근거 테스트:
  [ReplyRoutesMultipartTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt),
  [VerifiedImagePayloadHandleTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/VerifiedImagePayloadHandleTest.kt),
  [MessageSenderBytesAdapterTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MessageSenderBytesAdapterTest.kt),
  [MediaPreparationServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/MediaPreparationServiceTest.kt)

### 6. staged base64 future path

- 상태: `closed`
- 근거 코드:
  [Base64ImageIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/Base64ImageIngressService.kt),
  [ImagePayloadHandle.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ImagePayloadHandle.kt)
- 최종 판단:
  base64 ingress가 다시 필요해져도 boundary에서 즉시 decode되고, spill-backed verified handle로 core에 전달된다.
  core는 base64 string이나 large byte array에 재결합되지 않는다.

### 7. snapshot actor ownership and missing grace

- 상태: `closed`
- 근거 코드:
  [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt),
  [SnapshotMissingPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotMissingPolicy.kt),
  [SnapshotStateStore.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/SnapshotStateStore.kt),
  [SnapshotObserver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/SnapshotObserver.kt)
- 최종 판단:
  snapshot 상태는 actor 내부에서만 합쳐지고, missing은 `Pending / Confirmed`로 구분된다.
  first miss는 무이벤트, pending restore는 quiet, confirmed restore만 emit한다.

### 8. ingress progress watermark separation

- 상태: `closed`
- 근거 코드:
  [CommandIngressService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt)
- 최종 판단:
  ingress는 `lastPolledLogId`, `lastBufferedLogId`, `lastCommittedLogId`를 분리해 poll/buffer/commit progress를 드러낸다.
  observation은 global buffered dispatch 기준으로 진행되고, checkpoint는 completed prefix만 commit한다.
- 근거 테스트:
  [CommandIngressServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ingress/CommandIngressServiceTest.kt),
  [ObserverHelperLogicTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt)

### 9. typed boundary uplift

- 상태: `closed`
- 근거 코드:
  [PeriodSpec.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/PeriodSpec.kt),
  [PeriodSpecParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/PeriodSpecParser.kt),
  [ThreadOriginMetadata.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/model/ThreadOriginMetadata.kt),
  [ThreadOriginMetadataDecoder.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ThreadOriginMetadataDecoder.kt),
  [JsonIdArrayParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/JsonIdArrayParser.kt),
  [RoomMetaParser.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RoomMetaParser.kt),
  [NonOpenRoomNameResolver.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/NonOpenRoomNameResolver.kt),
  [RoomStatisticsService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RoomStatisticsService.kt),
  [ThreadListingService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ThreadListingService.kt),
  [MemberRepositoryMetadata.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepositoryMetadata.kt)
- 최종 판단:
  raw period string과 thread origin JSON은 service 안쪽이 아니라 typed boundary로 승격됐다.
  metadata helper는 JSON id 파싱 / room meta parse / non-open room naming으로 분리됐다.

### 10. reply worker lifecycle and outbox delivery policy

- 상태: `closed`
- 근거 코드:
  [ReplyAdmissionService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt),
  [WebhookDeliveryPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookDeliveryPolicy.kt),
  [WebhookOutboxDispatcher.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt)
- 최종 판단:
  reply worker는 mailbox state, queue depth, age를 debug snapshot에 드러낸다.
  outbox retry policy는 legacy `H2cDispatcher` 상수가 아니라 현재 경로의 `WebhookDeliveryPolicy`만 본다.

## reviewer 메모

이번 reviewer pack에서 기준으로 볼 문서는 같은 날짜의 guide / change-rationale / final-closure / test-results / manifest / index 여섯 개뿐이다.
과거 2026-03-30 문서와 code-focus 문서는 reviewer 기준에서 제외한다.
