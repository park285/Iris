# Compatibility Unification Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Remove the remaining legacy naming and compatibility layers so Iris exposes one strict config contract, one signed auth path, and one typed image-bridge protocol path.

**Architecture:** Promote the strict path to the default public API and delete the compatibility aliases instead of carrying dual routes or dual DTOs. Keep runtime defaults such as H2C unchanged, but remove old names, fallback headers, and JSON wrapper entry points from the hot paths and public surface.

**Tech Stack:** Kotlin, Ktor, kotlinx.serialization, Rust reqwest/tokio.

---

### Task 1: Collapse Config To One Contract

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/main/java/party/qwer/iris/ConfigContract.kt`
- Modify: `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- Modify: `app/src/main/java/party/qwer/iris/model/ConfigResponse.kt`
- Modify: `app/src/main/java/party/qwer/iris/model/ConfigUpdateResponse.kt`
- Delete: `app/src/main/java/party/qwer/iris/model/ConfigResponseV2.kt`
- Delete: `app/src/main/java/party/qwer/iris/model/ConfigUpdateResponseV2.kt`
- Modify/Delete tests: `app/src/test/java/party/qwer/iris/ConfigContractTest.kt`, `app/src/test/java/party/qwer/iris/ConfigV2ContractTest.kt`

- [ ] Write failing tests for strict `/config` only
- [ ] Run targeted config tests and confirm failure
- [ ] Remove `/config/v2` route, v2 DTOs, and legacy snapshot/effective fields from the default contract
- [ ] Re-run targeted config tests

### Task 2: Remove Token Fallback

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/test/java/party/qwer/iris/RequestAuthenticatorTest.kt`
- Modify: `tools/iris-ctl/src/auth.rs`
- Modify: `tools/iris-ctl/src/api.rs`
- Modify: `tools/iris-ctl/src/sse.rs`

- [ ] Write failing tests for signed-only auth
- [ ] Run targeted Kotlin and cargo tests and confirm failure
- [ ] Remove `X-Bot-Token` acceptance in server while keeping signed headers in `iris-ctl`
- [ ] Re-run targeted Kotlin and cargo tests

### Task 3: Remove JSON Wrapper Compatibility

**Files:**
- Modify: `imagebridge-protocol/src/main/kotlin/party/qwer/iris/ImageBridgeProtocol.kt`
- Modify: `app/src/main/java/party/qwer/iris/UdsImageBridgeClient.kt`
- Modify: `bridge/src/main/java/party/qwer/iris/imagebridge/runtime/ImageBridgeRequestHandler.kt`
- Modify: `bridge/src/main/java/party/qwer/iris/imagebridge/runtime/BridgeHandshakeValidator.kt`
- Modify tests: `app/src/test/java/party/qwer/iris/ImageBridgeProtocolTest.kt`, `app/src/test/java/party/qwer/iris/UdsImageBridgeClientTest.kt`, `bridge/src/test/java/party/qwer/iris/imagebridge/runtime/ImageBridgeRefactorTest.kt`

- [ ] Write failing tests for typed-only protocol usage
- [ ] Run targeted app/bridge protocol tests and confirm failure
- [ ] Delete public `JSONObject` compatibility helpers and move tests to typed DTOs
- [ ] Re-run targeted protocol tests

### Task 4: Docs And Verification

**Files:**
- Modify: `README.md`
- Modify: `docs/client-webhook-type-attachment.md`
- Modify: `.tasklists/compatibility-unification/todolist.md`

- [ ] Update docs to remove `/config/v2`, legacy token-only auth, and JSON wrapper examples
- [ ] Run `./gradlew lint ktlintCheck assembleDebug assembleRelease test`
- [ ] Run `cargo test`
- [ ] Run `git diff --check`
