# Post-Refactor Optimizations Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Harden the remaining hot paths after the large refactor by adding streaming-aware request auth, bounded outbox delivery backpressure, typed image bridge protocol DTOs, and a versioned `/config/v2` contract without regressing existing callers.

**Architecture:** Keep existing runtime defaults and behavioral compatibility, but move the remaining high-risk edges onto clearer contracts. `/config` stays compatibility-first, `/config/v2` becomes the strict nested contract, request signing protects the full normalized request target, the CLI learns signed requests, and outbox dispatch becomes bounded rather than unbounded in-memory fan-out.

**Tech Stack:** Kotlin, Ktor 3.4, kotlinx.serialization, OkHttp, Rust reqwest/tokio, existing Iris bridge protocol module.

---

### Task 1: Auth Streaming And Signed Client

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt`
- Modify: `app/src/test/java/party/qwer/iris/RequestAuthenticatorTest.kt`
- Modify: `app/src/test/java/party/qwer/iris/IrisServerRequestBodyTest.kt`
- Modify: `tools/iris-ctl/src/sse.rs`
- Modify: `tools/iris-ctl/src/api.rs`
- Modify: `tools/iris-ctl/src/config.rs`
- Modify: `tools/iris-ctl/Cargo.toml`
- Add/Modify: `tools/iris-ctl/src/auth.rs`

- [ ] **Step 1: Write failing tests for full-target signing and request size enforcement**
  Add tests for signed GET including normalized query string, oversized protected request body rejection, and legacy token-only fallback remaining intact.

- [ ] **Step 2: Run targeted app tests to verify they fail for the new cases**
  Run: `./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.RequestAuthenticatorTest" --tests "party.qwer.iris.IrisServerRequestBodyTest"`

- [ ] **Step 3: Implement canonical request target handling and bounded request body reading**
  Use a shared helper that signs `METHOD + PATH_WITH_NORMALIZED_QUERY + TIMESTAMP + NONCE + SHA256(body)` and route protected POST bodies through a bounded reader before JSON decode.

- [ ] **Step 4: Add signed-request support to `iris-ctl` without breaking legacy token-only auth**
  Keep default compatibility, but when a token exists, generate `X-Iris-Timestamp`, `X-Iris-Nonce`, `X-Iris-Signature` from the outgoing request and still send `X-Bot-Token`.

- [ ] **Step 5: Re-run targeted app and CLI verification**
  Run the same app tests plus `cargo test` or `cargo check` for `tools/iris-ctl` and confirm green.

### Task 2: Outbox Dispatcher Backpressure

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt`
- Modify: `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutbox.kt`
- Modify: `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxTest.kt`
- Add/Modify: `app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt`

- [ ] **Step 1: Write failing tests for bounded partition queues and retry-on-saturation**
  Cover the case where partition capacity is exhausted and entries are rescheduled instead of being buffered unboundedly.

- [ ] **Step 2: Run targeted outbox tests to verify failure**
  Run: `./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.delivery.webhook.WebhookOutboxTest" --tests "party.qwer.iris.delivery.webhook.WebhookOutboxDispatcherTest"`

- [ ] **Step 3: Replace `Channel.UNLIMITED` with bounded partition channels and non-blocking enqueue**
  Preserve room partition ordering, avoid over-claiming from the store, and mark overflowed claims back to retry with a short delay.

- [ ] **Step 4: Keep current H2C default and delivery semantics stable**
  Do not change transport defaults; only change buffering and retry behavior.

- [ ] **Step 5: Re-run targeted outbox tests**
  Confirm retry scheduling and bounded queue behavior are both green.

### Task 3: Typed Image Bridge Protocol

**Files:**
- Modify: `imagebridge-protocol/build.gradle.kts`
- Modify: `imagebridge-protocol/src/main/kotlin/party/qwer/iris/ImageBridgeProtocol.kt`
- Modify: `app/src/main/java/party/qwer/iris/UdsImageBridgeClient.kt`
- Modify: `bridge/src/main/java/party/qwer/iris/imagebridge/runtime/ImageBridgeRequestHandler.kt`
- Modify: `app/src/test/java/party/qwer/iris/ImageBridgeProtocolTest.kt`
- Modify: `app/src/test/java/party/qwer/iris/UdsImageBridgeClientTest.kt`
- Modify: `bridge/src/test/java/party/qwer/iris/imagebridge/runtime/ImageBridgeRefactorTest.kt`

- [ ] **Step 1: Write failing tests for DTO encode/decode and dual-read compatibility**
  Add coverage for typed request/response DTOs, protocol version preservation, and legacy JSON fallback if still needed during migration.

- [ ] **Step 2: Run targeted protocol tests to verify failure**
  Run: `./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.ImageBridgeProtocolTest" --tests "party.qwer.iris.UdsImageBridgeClientTest" && ./gradlew :bridge:testDebugUnitTest --tests "party.qwer.iris.imagebridge.runtime.ImageBridgeRefactorTest"`

- [ ] **Step 3: Implement `@Serializable` protocol DTOs behind the existing frame transport**
  Keep frame size guards, request token support, and `protocolVersion`, but move parsing/building off `JSONObject` into typed DTOs.

- [ ] **Step 4: Update app client and bridge handler to use the typed protocol path**
  Ensure both send-image and health requests use the same typed model and preserve current semantics.

- [ ] **Step 5: Re-run targeted protocol tests**
  Confirm app and bridge protocol suites pass.

### Task 4: Versioned `/config/v2` Contract

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/main/java/party/qwer/iris/ConfigContract.kt`
- Modify: `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- Add/Modify: `app/src/main/java/party/qwer/iris/model/ConfigResponseV2.kt`
- Add/Modify: `app/src/main/java/party/qwer/iris/model/ConfigUpdateResponseV2.kt`
- Modify: `app/src/test/java/party/qwer/iris/ConfigContractTest.kt`
- Add/Modify: `app/src/test/java/party/qwer/iris/ConfigV2ContractTest.kt`

- [ ] **Step 1: Write failing tests for `/config` compatibility and `/config/v2` strict nested responses**
  Verify `/config` still exposes compatibility fields while `/config/v2` returns only the nested shape.

- [ ] **Step 2: Run targeted config contract tests to verify failure**
  Run: `./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.ConfigContractTest" --tests "party.qwer.iris.ConfigV2ContractTest" --tests "party.qwer.iris.ConfigRequestCompatibilityTest"`

- [ ] **Step 3: Implement dedicated v2 DTOs and route aliases**
  Keep `/config` compatibility-focused, add `/config/v2` GET and POST variants using the strict nested contract, and reuse the same update logic.

- [ ] **Step 4: Update docs for the versioned contract**
  Mention `/config` compatibility mode and `/config/v2` strict mode in the README or relevant client-facing docs.

- [ ] **Step 5: Re-run targeted config tests**
  Confirm compatibility and v2 behavior both pass.

### Task 5: Full Verification And Reconciliation

**Files:**
- Modify as needed: `README.md`
- Modify as needed: `docs/agent-reference.md`
- Modify as needed: `docs/client-webhook-type-attachment.md`
- Modify as needed: `.tasklists/post-refactor-optimizations/todolist.md`

- [ ] **Step 1: Run the full quality gate**
  Run: `./gradlew lint ktlintCheck assembleDebug assembleRelease test`

- [ ] **Step 2: Run diff hygiene**
  Run: `git diff --check`

- [ ] **Step 3: Run CLI verification**
  Run: `cd tools/iris-ctl && cargo test`

- [ ] **Step 4: Update checklist and reconcile remaining risk**
  Record exactly what changed, what stayed compatible, and any unverified runtime/device-only behavior.
