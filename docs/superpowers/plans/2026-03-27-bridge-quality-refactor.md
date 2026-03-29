# Bridge Quality Refactor Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Deliver behavior-preserving bridge refactors, low-risk performance improvements, and a repository-level quality manager.

**Architecture:** Keep public bridge contracts stable while splitting orchestration from transport and Kakao runtime details. Introduce repository-wide verification through one root entrypoint and focused bridge guardrails.

**Tech Stack:** Kotlin, Android `LocalSocket`/`LocalServerSocket`, OkHttp, coroutines, shell scripts, Gradle, Go Makefile, npm/tsx.

---

### Task 1: Add Regression Tests For Refactor Targets

**Files:**
- Modify: `app/src/test/java/party/qwer/iris/UdsImageBridgeClientTest.kt`
- Create: `bridge/src/test/java/party/qwer/iris/bridge/ImageBridgeRequestHandlerTest.kt`
- Modify: `app/src/test/java/party/qwer/iris/bridge/H2cDispatcherClientConfigTest.kt`

- [ ] Write failing tests for extracted bridge transport behavior
- [ ] Run focused tests and verify failure reason matches the missing structure
- [ ] Implement the minimal extraction seams to satisfy the tests
- [ ] Re-run focused tests and keep behavior green

### Task 2: Refactor App Bridge Delivery Path

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/bridge/H2cDispatcher.kt`
- Create: `app/src/main/java/party/qwer/iris/bridge/WebhookRequestFactory.kt`
- Create: `app/src/main/java/party/qwer/iris/bridge/WebhookDeliveryClient.kt`

- [ ] Keep `H2cDispatcher` as orchestration only
- [ ] Move request creation and async OkHttp execution into focused classes
- [ ] Preserve route admission and retry behavior
- [ ] Re-run bridge unit tests

### Task 3: Refactor LSPosed Image Bridge Runtime

**Files:**
- Modify: `bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeServer.kt`
- Modify: `bridge/src/main/java/party/qwer/iris/bridge/KakaoImageSender.kt`
- Create: `bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeRequestHandler.kt`
- Create: `bridge/src/main/java/party/qwer/iris/bridge/ChatRoomResolver.kt`
- Create: `bridge/src/main/java/party/qwer/iris/bridge/KakaoSendInvocationFactory.kt`

- [ ] Add request handler abstraction and test seam
- [ ] Move client handling off the accept loop
- [ ] Cache reflection metadata and split Kakao responsibilities
- [ ] Re-run focused tests

### Task 4: Introduce Repository Quality Manager

**Files:**
- Create: `scripts/verify-all.sh`
- Create: `scripts/check-bridge-boundaries.sh`
- Modify: `bridge/build.gradle.kts`
- Modify: `.tasklists/bridge-refactor-quality/todolist.md`

- [ ] Add one root verification command that composes Gradle, npm, Go, and shell checks
- [ ] Enforce bridge-module lint and ktlint consistently
- [ ] Add lightweight bridge architecture guardrails
- [ ] Verify scripts locally

### Task 5: Full Verification And Reconciliation

**Files:**
- Modify: `.tasklists/bridge-refactor-quality/todolist.md`

- [ ] Run focused test suites for changed bridge paths
- [ ] Run repository verification commands that are practical in this environment
- [ ] Reconcile implemented scope against the approved design
- [ ] Report verified results and any remaining unverified risk
