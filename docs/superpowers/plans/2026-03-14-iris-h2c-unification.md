# Iris H2C Unification Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Make Iris inbound `/health`, `/ready`, and `/reply` genuinely accept h2c, keep outbound webhooks on h2c, and redeploy the KR instance with verification.

**Architecture:** Replace the current unsupported h2c expectation on the CIO server path with an implementation that can actually negotiate h2c. Because official Ktor 3.4.1 docs only expose server h2c support on Netty, the change must verify whether Netty is viable inside the Android/redroid runtime; if not, stop before rollout rather than shipping a false “fully h2c” claim.

**Tech Stack:** Kotlin, Android app module, Ktor 3.4.1 server, OkHttp, redroid, ADB, KR remote staging under `/root/work/Iris`.

---

## Chunk 1: Confirm Server Stack and Tests

### Task 1: Add inbound h2c failing tests

**Files:**
- Modify: `app/build.gradle.kts`
- Create: `app/src/test/java/party/qwer/iris/IrisServerH2cTest.kt`
- Reference: `app/src/main/java/party/qwer/iris/IrisServer.kt`

- [ ] **Step 1: Add the smallest test dependency needed for a local h2c-capable server/client test**
- [ ] **Step 2: Write a failing test that proves the current server does not answer h2c prior-knowledge on `/health`**
- [ ] **Step 3: Run only the new test and verify it fails for the expected transport reason**

### Task 2: Prove the replacement server path locally

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/IrisServer.kt`
- Modify: `app/src/main/java/party/qwer/iris/Main.kt` only if startup wiring changes
- Test: `app/src/test/java/party/qwer/iris/IrisServerH2cTest.kt`

- [ ] **Step 1: Implement the minimal server configuration change required for h2c**
- [ ] **Step 2: Re-run the h2c test and verify `/health` succeeds over h2c**
- [ ] **Step 3: Extend the test to cover `/reply` request acceptance over h2c**
- [ ] **Step 4: Run targeted existing server-related tests to catch regressions**

## Chunk 2: Build, Deploy, and Verify

### Task 3: Build and stage APKs safely

**Files:**
- Modify only if needed: `build.md`, `README.md`
- Output: `output/Iris-debug.apk`, `output/Iris-release.apk`

- [ ] **Step 1: Build debug and release APKs locally from `/home/kapu/gemini/Iris`**
- [ ] **Step 2: Record hashes for built artifacts**
- [ ] **Step 3: If build/runtime constraints block the Netty path on Android, stop and document the blocker instead of deploying**

### Task 4: Back up KR staging and redeploy

**Files / Paths:**
- Remote backup path: `/root/work/Iris/.backup-<timestamp>`
- Remote staging path: `/root/work/Iris`
- Remote runtime artifact: `/data/local/tmp/Iris.apk`

- [ ] **Step 1: Create a timestamped backup on `100.100.1.4` before overwriting APKs or scripts**
- [ ] **Step 2: Sync verified APKs to `/root/work/Iris/output`**
- [ ] **Step 3: Restart Iris using the existing remote control flow**
- [ ] **Step 4: Verify remote h2c with `curl --http2-prior-knowledge http://127.0.0.1:3000/health`**
- [ ] **Step 5: Verify `/reply` over h2c with a safe non-delivery request and confirm no protocol error remains**

