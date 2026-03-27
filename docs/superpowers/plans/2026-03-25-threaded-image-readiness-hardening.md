# Threaded Image Readiness Hardening Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add missing operational hardening for threaded image graft by implementing agent self-health, daemon readiness state tracking plus endpoint, and Iris-side dynamic readiness admission.

**Architecture:** Keep the canonical runtime split intact: `frida/agent` emits structured health/readiness messages, `frida/daemon` owns state transitions and exposes a local readiness endpoint, and the Android Iris app consumes daemon readiness to fail closed for threaded image sends while leaving non-threaded sends unchanged. Avoid `frida-modern/`; integrate with the current working tree because app-side uncommitted changes already exist here.

**Tech Stack:** TypeScript + Frida Java bridge, Go daemon, Kotlin/Ktor Android app, node:test/tsx, Go `testing`, JUnit.

---

### Task 1: TS Agent Self-Health

**Files:**
- Modify: `frida/agent/thread-image-graft.ts`
- Modify: `frida/agent/shared/kakao.ts`
- Create or Modify: `frida/agent/test/thread-image-health.test.ts`

- [ ] **Step 1: Write failing tests for hook-spec validation and health snapshots**
- [ ] **Step 2: Run agent tests to verify the new tests fail**
  Run: `cd /home/kapu/gemini/Iris/frida/agent && npm test -- test/thread-image-health.test.ts`
- [ ] **Step 3: Add minimal health state and structured message helpers**
  Add per-hook install tracking for `b6`, `o`, `t`, `A`, `u`, last-seen timestamps, injection success/failure/skip counters, and a structured heartbeat payload.
- [ ] **Step 4: Add hook-spec/version gate before readiness becomes true**
  Verify declared class/method targets exist before installing hooks; if any target is missing, emit readiness false and do not claim healthy.
- [ ] **Step 5: Re-run targeted agent tests and then the full agent suite**
  Run: `cd /home/kapu/gemini/Iris/frida/agent && npm test`

### Task 2: Go Daemon Readiness State Machine

**Files:**
- Modify: `frida/daemon/internal/fridaapi/client.go`
- Modify: `frida/daemon/internal/fridaapi/client_real.go`
- Modify: `frida/daemon/internal/fridaapi/runtime.go`
- Modify: `frida/daemon/internal/lifecycle/runner.go`
- Modify: `frida/daemon/internal/app/config.go`
- Modify: `frida/daemon/internal/app/run.go`
- Modify: `frida/daemon/cmd/graft-daemon/main.go`
- Create or Modify: `frida/daemon/internal/app/readiness.go`
- Create or Modify: `frida/daemon/internal/app/readiness_test.go`
- Modify: `frida/daemon/internal/app/run_test.go`
- Modify: `frida/daemon/internal/lifecycle/runner_test.go`

- [ ] **Step 1: Write failing Go tests for readiness transitions and endpoint responses**
- [ ] **Step 2: Run targeted Go tests to verify they fail**
  Run: `cd /home/kapu/gemini/Iris/frida/daemon && go test ./internal/app ./internal/lifecycle`
- [ ] **Step 3: Add structured agent-message parsing and readiness state storage**
  Track `BOOTING`, `HOOKING`, `WARM`, `READY`, `DEGRADED`, `BLOCKED`, plus last heartbeat and per-hook install status.
- [ ] **Step 4: Expose a local HTTP readiness endpoint from the daemon**
  Add config for bind address if needed, respond with JSON that includes state and recent telemetry, and fail closed when heartbeat expires or required hooks are missing.
- [ ] **Step 5: Re-run targeted Go tests and then the daemon test suite**
  Run: `cd /home/kapu/gemini/Iris/frida/daemon && go test ./...`

### Task 3: Iris Dynamic Admission

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/ConfigProvider.kt`
- Modify: `app/src/main/java/party/qwer/iris/ConfigManager.kt`
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/main/java/party/qwer/iris/ReplyAdmission.kt`
- Modify: `app/src/main/java/party/qwer/iris/ReplyService.kt`
- Create or Modify: `app/src/main/java/party/qwer/iris/GraftReadinessClient.kt`
- Create or Modify: `app/src/main/java/party/qwer/iris/GraftReadinessState.kt`
- Create or Modify: `app/src/test/java/party/qwer/iris/GraftReadinessClientTest.kt`
- Create or Modify: `app/src/test/java/party/qwer/iris/ReplyAdmissionTest.kt`

- [ ] **Step 1: Write failing Kotlin tests for readiness polling and fail-closed admission**
- [ ] **Step 2: Run targeted Kotlin tests to verify they fail**
  Run: `cd /home/kapu/gemini/Iris && ./gradlew test --tests 'party.qwer.iris.GraftReadinessClientTest' --tests 'party.qwer.iris.ReplyAdmissionTest'`
- [ ] **Step 3: Add daemon readiness client and in-memory state**
  Poll the daemon endpoint or refresh on demand, keep a short TTL, and separate transport errors from explicit blocked/degraded readiness.
- [ ] **Step 4: Gate threaded image sends while leaving plain sends alone**
  Reject only requests that require image graft metadata when daemon readiness is not healthy; keep `/ready` dynamic instead of static JSON.
- [ ] **Step 5: Re-run targeted Kotlin tests and then the broader JVM test set**
  Run: `cd /home/kapu/gemini/Iris && ./gradlew test`

### Task 4: Integration Verification

**Files:**
- Update as needed: `docs/superpowers/specs/2026-03-25-session-chain-graft-design.md`

- [ ] **Step 1: Run agent, daemon, and app verification commands**
- [ ] **Step 2: Confirm requested remaining items are all covered**
- [ ] **Step 3: Summarize what changed, what was verified, and residual risks**
