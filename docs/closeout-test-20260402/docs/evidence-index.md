# Evidence Index

## 1. Traceability Matrix

| # | Review Item | Changed Files | Verification Tests | Evidence Files | Status | Residual Risk |
|---|-------------|---------------|--------------------|----------------|--------|---------------|
| 1 | Auth contract hardening | `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt` | `RequestAuthenticatorTest.kt`, `RequestAuthenticatorContractTest.kt`, `AuthContractVectors.kt`, `tests/auth_contract_test.rs` | `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.RequestAuthenticatorTest.xml`, `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.RequestAuthenticatorContractTest.xml`, `artifacts/patches/working-tree.patch`, `tests/iris_auth_vectors.json` | Closed | None |
| 2 | Reply admission ownership | `app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt` | `ReplyAdmissionServiceTest.kt`, `ReplyAdmissionTest.kt`, `ReplyRoutesTest.kt` | `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.reply.ReplyAdmissionServiceTest.xml`, `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.ReplyAdmissionTest.xml`, `artifacts/patches/working-tree.patch` | Closed | None |
| 3 | Ingress backlog restructure | `app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt` | `CommandIngressServiceTest.kt` | `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.ingress.CommandIngressServiceTest.xml`, `artifacts/patches/working-tree.patch` | Partial | No load test for sustained buffer saturation across partitions |
| 4 | H2c transport defaults | `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookHttpClientFactory.kt`, `WebhookDeliveryPolicy.kt`, `WebhookOutboxDispatcher.kt` | `H2cDispatcherClientConfigTest.kt`, `WebhookDeliveryClientTest.kt`, `WebhookOutboxDispatcherTest.kt` | `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.delivery.webhook.H2cDispatcherClientConfigTest.xml`, `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.delivery.webhook.WebhookDeliveryClientTest.xml`, `artifacts/patches/working-tree.patch` | Closed | None |
| 5 | Route boundary separation | `app/src/main/java/party/qwer/iris/http/QueryRoutes.kt`, `ReplyRoutes.kt`, `model/AllowlistedQueryRequests.kt` | `QueryRoutesTest.kt`, `ReplyRoutesTest.kt`, `ProtectedRouteRoleSeparationTest.kt`, `RouteAuthRoleTest.kt` | `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.http.QueryRoutesTest.xml`, `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.http.ReplyRoutesTest.xml`, `artifacts/test-results/junit/testDebugUnitTest/TEST-party.qwer.iris.http.ProtectedRouteRoleSeparationTest.xml`, `artifacts/patches/working-tree.patch` | Closed | None |
| 6 | Tools decomposition | `tools/iris-common/src/auth.rs`, `models.rs`, `api.rs`; `tools/iris-daemon/src/process.rs`, `config_sync.rs`, `main.rs`; `tools/iris-ctl/src/views/messages.rs`, `reply_modal.rs`, `events.rs`, `app.rs`, `sse.rs` | Kotlin: 734 tests (all passing); Rust: 120 tests (`iris-common` 16, `auth_contract_test` 1, `iris-ctl` 55, `iris-daemon` 48) | `artifacts/test-results/cargo-output.txt`, `artifacts/test-results/gradle-output.txt`, `artifacts/patches/working-tree.patch` | Partial | iris-ctl terminal-harness/manual smoke coverage is still missing |

---

## 2. Test Coverage Detail

### Item 1 — Auth Contract Hardening

**Covered:**
- `canonical request serializes protocol fields in signing order` — verifies field order in HMAC input
- `rejects legacy bot token without signature` — enforces non-null signature requirement
- `accepts valid signed request` — happy-path HMAC verification
- `rejects expired signed request` — timestamp window enforcement
- `rejects reused nonce` — replay prevention
- `signed GET binds query string into canonical request` — query-string tampering detection
- `invalid signature does not consume nonce before finalize succeeds` — nonce-before-finalize invariant
- `preverify and finalize authorize valid signed request` — streaming body auth path
- `body hash mismatch does not consume nonce before finalize succeeds` — finalize rejection without nonce expiry
- `rejects new nonce when cache is at capacity` — nonce cache capacity enforcement
- `expired nonce remains blocked until purge cadence runs` — purge-before-reuse semantics
- `AuthContractVectors` / `iris_auth_vectors.json` — cross-language HMAC vector verification
- Rust `canonical_request_serializes_protocol_fields_in_signing_order` — Rust-side canonical format parity

**Uncovered:**
- Concurrent nonce submission from two goroutines/threads simultaneously — timing-sensitive race not covered
- Malformed (non-hex) body SHA256 submitted in signature header — normalization rejects it (code path exists) but no explicit test

### Item 2 — Reply Admission Ownership

**Covered:**
- `rejected enqueue aborts request inside admission` — ownership of cleanup on QUEUE_FULL
- `exposes explicit lifecycle ownership` — STOPPED → RUNNING → TERMINATED state machine
- `accepts request when started and worker available` — baseline acceptance
- `rejects when not started` — pre-start gate
- `rejects after shutdown` — post-shutdown gate
- `rejects when max workers exceeded` — slot exhaustion (QUEUE_FULL)
- `reuses existing worker for same key` — worker key binding
- `rejects when per-worker queue is full` — per-worker channel saturation
- `restart clears workers and accepts new requests` — restart transition
- `stale worker retry creates new worker` — idle timeout expiry and replacement
- `idle worker release frees worker slot for a different key` — slot reclamation after idle
- `shutdown keeps service terminated` — restart-after-shutdown blocked
- `enqueueSuspend returns accepted when running` — suspend path parity
- `concurrent enqueue during shutdown returns shutdown status` — race path
- `shutdown closes actor infrastructure` — command channel and scope cancellation
- `debugSnapshotSuspend exposes queue depth and worker age` — observability

**Uncovered:**
- Worker error mid-job does not consume next job's slot (exception catch path tested only via log, not by assertion on subsequent enqueue)

### Item 3 — Ingress Backlog Restructure

**Covered:**
- `checkChange initializes lastLogId on first call` — seed from DB vs. checkpoint
- `checkChange loads persisted lastLogId from journal` — checkpoint resume
- Partitioned dispatch polling, blocked-dispatch retry, duplicate fingerprinting
- `progressSnapshot` fields populated correctly

**Uncovered:**
- All partitions blocked simultaneously while new logs arrive — `remainingBufferCapacity` interaction not stress-tested
- Cross-partition signal wake after `signalBlockedDispatches` — verified by log only

### Item 4 — H2c Transport Defaults

**Covered:**
- `webhook transport defaults to h2c when unset` — env-absent default
- `shares dispatcher and connection pool across transport clients` — resource sharing
- `h2c transport sets maxRequestsPerHost` — H2C concurrency tuning
- `http1 transport keeps default maxRequests` — HTTP/1.1 conservative default
- `all clients have explicit writeTimeout set` — timeout presence
- `transport security mode defaults to loopback cleartext only` — security default
- `non loopback cleartext webhook is rejected in loopback mode` — non-loopback cleartext blocked
- `loopback cleartext webhook is allowed in loopback mode` — loopback cleartext allowed
- `private overlay cleartext webhook is rejected in loopback mode` — CGNAT blocked in loopback mode
- `non loopback cleartext webhook is allowed in private overlay mode` — CGNAT allowed in overlay mode

**Uncovered:**
- IPv6 private overlay address classification — `isTrustedPrivateWebhookUrl` has IPv6 branch, no test for ULA (`fc00::/7`)

### Item 5 — Route Boundary Separation

**Covered:**
- `query room summary returns allowlisted room data` — room summary route authentication and response
- `query member stats` — member stats route
- `query recent threads` — thread listing route
- `query recent messages returns decrypted allowlisted message data` — new endpoint including thread_id, decrypt
- `raw query endpoint is removed` — removal of legacy endpoint
- `ProtectedRouteRoleSeparationTest` — botControlToken role cannot access non-query routes and vice versa
- `RouteAuthRoleTest` — auth role binding per route segment

**Uncovered:**
- `maxBodyBytes` enforcement on `/query/recent-messages` for oversized payload — route installs with 256 KB cap but no explicit overflow test for the new endpoint

### Item 6 — Tools Decomposition

**Covered (Rust):**
- `auth_contract_test` suite against `iris_auth_vectors.json` — cross-language vector fidelity
- `views::messages::*`, `views::events::*`, `views::reply_modal::*` — iris-ctl projection/state behavior
- `sse::*` — SSE parsing and replay behavior
- `process::tests`, `launch_spec::tests`, `config_sync::tests`, `state::tests`, `rollback::tests` — daemon extraction and lifecycle logic

**Uncovered:**
- iris-ctl real terminal rendering / key binding behavior under a ratatui harness
- end-to-end manual smoke for the decomposed TUI flow

---

## 3. Evidence File Locations

| Artifact | Path in packet |
|----------|----------------|
| Patch (all changes) | `artifacts/patches/working-tree.patch` |
| Revision metadata | `artifacts/metadata/revision.txt` |
| Gradle test output | `artifacts/test-results/gradle-output.txt` |
| Cargo test output | `artifacts/test-results/cargo-output.txt` |
| Verification summary | `artifacts/test-results/verification-summary.md` |
| JUnit XML (all suites) | `artifacts/test-results/junit/testDebugUnitTest/TEST-*.xml` (107 XML files; 109 files total including 2 Gradle binary files) |
| Auth contract vectors | `tests/iris_auth_vectors.json` |
| RequestAuthenticator source | `app/src/main/java/party/qwer/iris/RequestAuthenticator.kt` |
| ReplyAdmissionService source | `app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt` |
| CommandIngressService source | `app/src/main/java/party/qwer/iris/ingress/CommandIngressService.kt` |
| WebhookHttpClientFactory source | `app/src/main/java/party/qwer/iris/delivery/webhook/WebhookHttpClientFactory.kt` |
| QueryRoutes source | `app/src/main/java/party/qwer/iris/http/QueryRoutes.kt` |
| ReplyRoutes source | `app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt` |
| tools/iris-common/auth.rs source | `tools/iris-common/src/auth.rs` |
| tools/iris-daemon/process.rs source | `tools/iris-daemon/src/process.rs` |
| iris-ctl views source | `tools/iris-ctl/src/views/` |
