# Evidence Index (Traceability Matrix)

## Reviewed Revision

- commit: `18a5f9bffc7450096f76e22aa0ad06a1a6e63587` + working tree
- generated at: 2026-04-02T02:15:00+09:00

---

## Traceability Matrix

| # | Review Item | 변경 파일 | 검증 테스트 | 증거 파일 | 상태 | 잔여 리스크 |
|---|-------------|-----------|-------------|-----------|------|------------|
| 1 | Auth canonical request contract | `IrisCanonicalRequest.kt`, `NonceWindow.kt`, `RequestAuthenticator.kt`, `auth.rs` | `RequestAuthenticatorTest` (11), `RequestAuthenticatorContractTest` (1), `auth_contract_test.rs` (7 vectors) | `iris_auth_vectors.json`, `junit/TEST-...RequestAuthenticatorTest.xml`, `junit/TEST-...RequestAuthenticatorContractTest.xml`, `cargo-test.txt` | **Partial** | empty/malformed nonce 미 fixture화, percent-encoding 이중 디코딩 미확인 |
| 2 | Reply admission cleanup ownership | `ReplyAdmissionService.kt`, `ReplyLaneJob.kt`, `ReplyAdmissionModels.kt`, `ReplyLaneJobProcessor.kt`, `ReplyService.kt` | `ReplyAdmissionServiceTest` (16), `ReplyServiceTest` (26) | `junit/TEST-...ReplyAdmissionServiceTest.xml`, `junit/TEST-...ReplyServiceTest.xml` | **Partial** | abort 중복 호출 카운트 없음, native handle 누수 미검증 |
| 3 | Reply route boundary (multipart) | `ReplyRoutes.kt`, `ValidatedReplyTarget.kt`, `MultipartReplyCollector.kt`, `MultipartImagePartStager.kt` | `ReplyRoutesTest`, `ReplyRoutesMultipartTest` | `junit/TEST-...ReplyRoutesTest.xml`, `junit/TEST-...ReplyRoutesMultipartTest.xml` | **Partial** | duplicate metadata 거부 테스트 없음, disconnect cleanup 미검증 |
| 4 | Ingress backlog structure | `CommandIngressService.kt`, `IngressBacklog.kt`, `IngressPartitioningPolicy.kt`, `ChatLogDispatch.kt`, `SenderNameResolver.kt`, `RoomMetadataResolver.kt` | `CommandIngressServiceTest` (14) | `junit/TEST-...CommandIngressServiceTest.xml` | **Partial** | gap-prefix commit 명시 assertion 없음, starvation 미확인 |
| 5 | Runtime composition split | `AppRuntime.kt`, `RuntimeBuilders.kt`, `ReplyRuntimeFactory.kt`, `PersistenceFactory.kt`, `SnapshotRuntimeFactory.kt` | `AppRuntimeWiringTest` (3) | `junit/TEST-...AppRuntimeWiringTest.xml` | **Closed** | — |
| 6 | Snapshot missing semantics | `snapshot/RoomSnapshotReadResult.kt`, `snapshot/SnapshotCoordinator.kt` | `SnapshotCoordinatorTest` | `junit/TEST-...SnapshotCoordinatorTest.xml` | **Closed** | — |
| 7 | Request body handle | `http/RequestBodyHandle.kt`, `http/RequestBodyReader.kt` | `RequestBodyReaderTest` | `junit/TEST-...RequestBodyReaderTest.xml` | **Closed** | — |
| 8 | h2c default / cleartext policy | `IrisServer.kt`, `WebhookHttpClientFactory.kt` | `H2cDispatcherClientConfigTest` (10), `IrisServerNettyTransportTest` (7) | `junit/TEST-...H2cDispatcherClientConfigTest.xml`, `junit/TEST-...IrisServerNettyTransportTest.xml` | **Partial** | env→config 체인 e2e 없음, 문서-구현-설정 삼자 정합성 미검증 |
| 9 | iris-ctl decomposition | `tools/iris-ctl/src/` (25 files) | workspace unit tests (55) | `cargo-test.txt` | **Partial** | terminal-harness e2e와 수동 smoke가 아직 없다 |
| 10 | iris-daemon LaunchSpec | `tools/iris-daemon/src/launch_spec.rs`, `tools/iris-daemon/src/process.rs` | `iris_control_path_defaults_to_expected_binary`, `render_template_json` | `cargo-test.txt` | **Closed** | — |

---

## 테스트 커버리지 상세

### Auth (항목 1)

**이미 검증됨 (Kotlin unit test)**:
- `canonical request serializes protocol fields in signing order`
- `accepts valid signed request`
- `rejects expired signed request` — stale timestamp
- `rejects reused nonce` — nonce replay
- `signed GET binds query string into canonical request`
- `invalid signature does not consume nonce` — bad signature
- `preverify and finalize authorize valid signed request`
- `body hash mismatch does not consume nonce before finalize succeeds` — body hash mismatch
- `rejects new nonce when cache is at capacity`
- `expired nonce remains blocked until purge cadence runs`
- `rejects legacy bot token without signature`

**크로스 언어 fixture 검증됨 (7 vectors)**:
- `get-config-empty-body` — 빈 body SHA-256
- `post-reply-json-body` — JSON body 포함 서명
- `get-stats-query-body-hash` — 쿼리 스트링 정렬
- `post-reply-unicode-body` — 한글 UTF-8 body SHA-256 **(신규)**
- `get-percent-encoded-path` — path percent-encoding **(신규)**
- `get-repeated-query-param` — 중복 쿼리 파라미터 정렬 **(신규)**
- `post-lowercase-method-normalization` — method 대소문자 정규화 **(신규)**

**미검증**:
- empty nonce (`""`) — fixture 없음, Kotlin unit test 없음
- malformed nonce (null byte, 특수문자) — fixture 없음, Kotlin unit test 없음

### Reply Admission (항목 2)

**이미 검증됨**:
- `rejected enqueue aborts request inside admission` — abort 1회 (`aborted.isCompleted` 검증)
- `rejects when not started` / `rejects after shutdown` — lifecycle 거부
- `concurrent enqueue during shutdown returns shutdown status` — race 거부
- `stale worker retry creates new worker` — worker replacement
- `idle worker release frees worker slot for a different key` — slot 해제
- `same key preserves send order` — 순서 보장
- `shutdown drains pending messages` — drain 검증

**미검증**:
- abort() 중복 호출 카운터 검증
- native image handle close 호출 경로 (abort 시)
- worker replacement 시 orphan job 부재 명시 assertion

### Multipart Routes (항목 3)

**이미 검증됨**:
- `multipart image request rejects image part before metadata` — metadata-before-image invariant
- `multipart image request rejects digest mismatch` — digest mismatch, sender 호출 0
- `multipart request with invalid auth is rejected before body buffering` — sig 조기 거절
- `multipart image request rejects invalid signature before staging images` — staging 전 차단

**미검증**:
- duplicate metadata part 거부 (코드 존재, 테스트 없음)
- client disconnect 시 partial cleanup
- staged handle release() 호출 경로 추적

### Ingress (항목 4)

**이미 검증됨**:
- `partitioned dispatch commits checkpoints in log order even when later partition finishes first` — out-of-order 완료
- `later logs keep buffering while an earlier partition is blocked` — partition isolation
- `bounded dispatch buffer stops polling only when capacity is saturated` — capacity 검증
- `image message forwards direct thread metadata from db columns` — 스레드 메타

**미검증**:
- 비연속 완료 구간에서 checkpoint gap skip 방지 단계별 assertion
- blocked partition unblock 후 starvation
- resolver cache stale semantics

### h2c Policy (항목 8)

**이미 검증됨**:
- `webhook transport defaults to h2c when unset` — 기본값
- `transport security mode defaults to loopback cleartext only` — 보안 모드
- `non loopback cleartext webhook is rejected in loopback mode` — loopback 강제
- `runtime server disables http2 and enables h2c by default` — Netty 설정

**미검증**:
- 환경변수 → ConfigManager → resolver 체인 e2e
- 문서(README)와 코드 기본값 일치 표

---

## 증거 파일 위치

| 증거 | 경로 |
|------|------|
| Revision metadata | `artifacts/metadata/revision.txt` |
| Working tree patch | `artifacts/patches/working-tree.patch` |
| Gradle JUnit XML (107 files) | `artifacts/test-results/junit/` |
| Cargo test output | `artifacts/test-results/cargo-test.txt` |
| Verification summary | `artifacts/test-results/verification-summary.md` |
| Auth contract fixture | `tests/contracts/iris_auth_vectors.json` |
| Kotlin source snapshot | `app/src/main/java/party/qwer/iris/` (190 files) |
| Kotlin test snapshot | `app/src/test/java/party/qwer/iris/` (109 files) |
| Rust source snapshot | `tools/` (43 files) |
