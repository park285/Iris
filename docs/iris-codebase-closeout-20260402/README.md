# Iris Codebase Closeout Package (2026-04-02)

## Reviewed Revision

| 항목 | 값 |
|------|-----|
| commit | `18a5f9bffc7450096f76e22aa0ad06a1a6e63587` |
| branch | `main` |
| working tree | `64168c012c1f4f660ce2b44ef2ef4adcbb2e7d82` (stash) |
| dirty files | 110 |
| generated at | 2026-04-02T02:15:00+09:00 |

이 패키지는 closeout 재검토에 필요한 선택 소스 스냅샷, working-tree patch,
테스트 산출물, 계약 fixture를 포함한다.
원 저장소의 기준 revision과 함께 봐야 전체 재현이 가능하다.

## Iris란

Iris는 Redroid(Android 에뮬레이터) 내부에서 root `app_process` 데몬으로 실행되는 headless 봇 프레임워크다.
KakaoTalk SQLite DB를 폴링하여 명령을 파싱하고, 웹훅을 디스패치하며,
`/reply` HTTP 요청을 통해 KakaoTalk 메시지를 전송한다.

## 아키텍처 개요

```
AppRuntime.start()
│
├── ConfigManager                          설정 전체 진입점
│
├── UdsImageBridgeClient                   LSPosed 브릿지 UDS 소켓
│
├── ReplyRuntimeFactory.create()
│   └── ReplyService                       Reply 파이프라인 통합 (send, status, shutdown)
│       └── ReplyAdmissionService          큐 진입 검증·레인 관리
│           └── ReplyLaneJobProcessor      레인 작업 처리기
│
├── RuntimeBuilders.createStorageRuntime()
│   ├── KakaoDB                            KakaoTalk SQLite 접근
│   ├── KakaoDbSqlClient                   타입드 쿼리 실행 래퍼
│   └── MemberRepository                   멤버 데이터 통합
│       ├── RoomCatalogService             방 카탈로그
│       ├── MemberListingService           멤버 목록
│       ├── RoomStatisticsService          방 통계
│       ├── ThreadListingService           스레드 목록
│       └── MemberIdentityResolver         신원 해석
│
├── PersistenceFactory.createSqliteRuntime()
│   ├── SqliteDriver                       Iris 자체 SQLite (iris.db)
│   ├── WebhookDeliveryStore               웹훅 아웃박스 영속화
│   ├── CheckpointJournal                  DB 폴링 체크포인트
│   ├── RoomEventStore                     방 이벤트 영속화
│   └── SseEventStore                      SSE 이벤트 영속화
│
├── WebhookOutboxDispatcher                아웃박스 폴링·재시도 루프
│
├── SseEventBus                            인메모리 SSE 브로드캐스트
│
├── SnapshotRuntimeFactory.create()
│   ├── SnapshotCoordinator                스냅샷 갱신 조정
│   ├── CommandIngressService              DB → 명령 인그레스
│   │   └── IngressBacklog                 파티션 백로그 엔진
│   ├── DBObserver                         KakaoDB 폴링 루프
│   └── SnapshotObserver                   스냅샷 변경 처리 루프
│
└── IrisServer (Ktor/Netty, h2c 기본)
    ├── RequestAuthenticator               HMAC-SHA256 인바운드 인증
    │   ├── IrisCanonicalRequest           서명 정규화 값 객체
    │   └── NonceWindow                    재전송 방지 nonce 창
    ├── /reply, /reply-image               Reply 라우트
    │   ├── ValidatedReplyTarget           대상 검증 값 객체
    │   └── MultipartReplyCollector        멀티파트 수집기
    ├── /health, /bridge/health            헬스 체크
    ├── /config                            설정 조회·수정
    ├── /rooms, /members, /stats           멤버·방 조회
    └── /query                             커스텀 쿼리
```

**셧다운**: HTTP 서버 → 옵저버 → 프로필 인덱서 → 웹훅 → 인그레스 → SSE → Reply → 영속화 → SQLite 순 16단계.

## Rust 도구 의존 관계

```
iris-common (라이브러리)
├── auth.rs          HMAC 서명 프로토콜 (Kotlin과 계약 공유)
├── config.rs        IrisConnection trait
├── models.rs        공유 DTO (ReplyRequest, RoomSummary, ...)
└── api.rs           IrisApi (reqwest HTTP 클라이언트)
       │
  ┌────┴────┐
  │         │
iris-ctl  iris-daemon
(TUI)     (감시자)

iris-ctl:  IrisApi 전체 소비 (조회/reply/SSE)
iris-daemon: health/ready/bridge 3개만 소비 (제어 플레인)
```

## 패키지 구성

```
iris-codebase-closeout-20260402/
│
├── README.md                              ← 이 파일
├── repo-README.md                         레포지터리 원본 README
├── agent-reference.md                     아키텍처·런타임 참조 문서
│
├── app/src/main/java/party/qwer/iris/     Kotlin 서버 소스 (190개)
│   ├── (루트)                             핵심 서비스, 인증, 설정, DB 접근
│   ├── config/                            설정 필드·경로·정책·영속화
│   ├── delivery/webhook/                  웹훅 아웃박스·전송·재시도
│   ├── http/                              HTTP 라우트·인증·멀티파트
│   ├── ingress/                           명령 수신·파티셔닝·백로그
│   ├── model/                             도메인 모델·DTO
│   ├── persistence/                       SQLite 영속화 (iris.db)
│   ├── reply/                             Reply 큐·상태 머신·전송
│   ├── snapshot/                          방 스냅샷·diff·이벤트
│   ├── storage/                           KakaoDB 쿼리 계층
│   └── util/                              직렬화 유틸
│
├── app/src/test/java/party/qwer/iris/     Kotlin 테스트 (109개)
│   ├── (루트)                             핵심 서비스 테스트
│   ├── config/                            설정 테스트
│   ├── delivery/webhook/                  웹훅 테스트
│   ├── http/                              HTTP 라우트 테스트
│   ├── ingress/                           인그레스 테스트
│   ├── persistence/                       영속화 테스트
│   ├── reply/                             Reply 테스트
│   ├── snapshot/                          스냅샷 테스트
│   └── storage/                           쿼리 테스트
│
├── tests/contracts/
│   └── iris_auth_vectors.json             Kotlin/Rust 크로스 언어 서명 계약 벡터
│
├── tools/iris-common/                     Rust 공유 라이브러리 (src + tests)
├── tools/iris-ctl/src/                    Rust TUI 클라이언트
├── tools/iris-daemon/src/                 Rust 감시 데몬
│
├── docs/
│   ├── executive-closeout.md              종결 판정 + 상태 라벨 + 잔여 리스크
│   ├── evidence-index.md                  traceability matrix + 테스트 커버리지 상세
│   └── detailed-rationale.md              핵심 설계 판단 before/after + 증거
│
├── artifacts/
│   ├── metadata/revision.txt              대상 revision 식별
│   ├── patches/working-tree.patch         uncommitted 변경 전체 (20,674줄)
│   └── test-results/
│       ├── verification-summary.md        검증 요약
│       ├── junit/ (107 files)             Gradle JUnit XML 결과
│       └── cargo-test.txt                 Rust 테스트 전체 출력
```

## 핵심 인터페이스

| 이름 | 위치 | 역할 |
|------|------|------|
| `ReplyLaneJob` | `reply/ReplyLaneJob.kt` | 레인 큐 작업 계약 (`prepare`/`abort`/`send`) |
| `ReplyAdmissionService` | `reply/ReplyAdmissionService.kt` | 큐 진입 검증·워커 관리·거절 cleanup |
| `RequestAuthenticator` | `RequestAuthenticator.kt` | HMAC-SHA256 인바운드 요청 인증 |
| `IrisCanonicalRequest` | `IrisCanonicalRequest.kt` | 서명 정규화 프로토콜 값 객체 |
| `NonceWindow` | `NonceWindow.kt` | 재전송 방지 nonce 보존 정책 |
| `CommandIngressService` | `ingress/CommandIngressService.kt` | DB 폴링 → 명령 디스패치 orchestration |
| `IngressBacklog` | `ingress/IngressBacklog.kt` | 파티션별 buffer/pending/blocked 전이 엔진 |
| `WebhookOutboxDispatcher` | `delivery/webhook/WebhookOutboxDispatcher.kt` | 아웃박스 폴링·클레임·재시도 루프 |
| `WebhookDeliveryPolicy` | `delivery/webhook/WebhookDeliveryPolicy.kt` | 재시도 상한·배치·폴링·만료 파라미터 |
| `SnapshotCoordinator` | `snapshot/SnapshotCoordinator.kt` | 스냅샷 갱신 조정·missing 처리 |
| `MultipartReplyCollector` | `http/MultipartReplyCollector.kt` | 멀티파트 이미지 수집·검증 |
| `ValidatedReplyTarget` | `http/ValidatedReplyTarget.kt` | room/thread 대상 검증 값 객체 |
| `IrisConnection` (trait) | `tools/iris-common/src/config.rs` | Rust 클라이언트 연결 정보 계약 |
| `IrisApi` | `tools/iris-common/src/api.rs` | Rust HTTP 클라이언트 전체 |
| `LaunchSpec` | `tools/iris-daemon/src/launch_spec.rs` | 프로세스 실행 명세 값 객체 |

## 핵심 모델

| 이름 | 위치 | 역할 |
|------|------|------|
| `ReplyRequest` | `model/` | `/reply` HTTP 수신 페이로드 |
| `ReplyType` | `model/` | TEXT / IMAGE / IMAGE_MULTIPLE / MARKDOWN |
| `ReplyLifecycleState` | `model/` | queued → preparing → prepared → sending → completed/failed |
| `ReplyImageMetadata` | `model/` | 이미지 Reply 메타 (room, parts 목록) |
| `RoomEvent` (sealed) | `model/` | MemberEvent / NicknameChange / RoleChange / ProfileChange |
| `ConfigValues` | `model/` | 런타임 적용 설정 전체 |
| `MemberInfo` | `model/` | 멤버 1명 정보 (userId, nickname, role) |
| `WebhookOutboxStatus` | `model/` | PENDING / SENDING / RETRY / SENT / DEAD |
| `ChatLogDispatch` | `ingress/ChatLogDispatch.kt` | 인그레스 디스패치 단위 |
| `IngressPartitioningPolicy` | `ingress/IngressPartitioningPolicy.kt` | 파티션 수·버퍼 크기 설정 |
| `ReplyAdmissionModels` | `reply/ReplyAdmissionModels.kt` | admission 상태·결과·lifecycle enum |
| `StorageDtos` | `storage/StorageDtos.kt` | KakaoDB 조회 결과 (RoomRow, FriendRow 등) |

## 테스트 커버리지 개요

| 영역 | 테스트 파일 수 | 대표 테스트 |
|------|---------------|-------------|
| 인증 | 2 | `RequestAuthenticatorTest` (11), `RequestAuthenticatorContractTest` (1) |
| Reply 파이프라인 | 9 | `ReplyServiceTest` (26), `ReplyAdmissionServiceTest` (16) |
| HTTP 라우트 | 11 | `ReplyRoutesTest`, `QueryRoutesTest`, `ConfigRoutesTest` |
| 인그레스 | 1 | `CommandIngressServiceTest` (14) |
| 웹훅 전송 | 12 | `WebhookOutboxDispatcherTest`, `H2cDispatcherClientConfigTest` (10) |
| 스냅샷 | 4 | `SnapshotCoordinatorTest`, `RoomSnapshotAssemblerTest` |
| 영속화 | 10 | `SqliteWebhookDeliveryStoreTest`, `SqliteCheckpointJournalTest` |
| 런타임 와이어링 | 1 | `AppRuntimeWiringTest` (3) |
| 설정 | 10 | `ConfigManagerStateTest`, `ConfigPolicyTest` |
| Rust (iris-common) | 1 + inline | `auth_contract_test` (계약 벡터), 모델 파싱 |
| Rust (iris-ctl) | inline | 메시지 투영, 멤버 필터, 쿼리 매핑 |
| Rust (iris-daemon) | inline | 템플릿 렌더링, LaunchSpec 경로 |

## 검증 재현

```bash
# Kotlin 대상 테스트
./gradlew :app:testDebugUnitTest \
  --tests 'party.qwer.iris.RequestAuthenticatorTest' \
  --tests 'party.qwer.iris.RequestAuthenticatorContractTest' \
  --tests 'party.qwer.iris.reply.ReplyAdmissionServiceTest' \
  --tests 'party.qwer.iris.ReplyServiceTest' \
  --tests 'party.qwer.iris.http.ReplyRoutesTest' \
  --tests 'party.qwer.iris.http.ReplyRoutesMultipartTest' \
  --tests 'party.qwer.iris.ingress.CommandIngressServiceTest' \
  --tests 'party.qwer.iris.delivery.webhook.H2cDispatcherClientConfigTest' \
  --tests 'party.qwer.iris.IrisServerNettyTransportTest' \
  --tests 'party.qwer.iris.AppRuntimeWiringTest'

# Rust 대상 테스트
cargo test -p iris-common --manifest-path tools/Cargo.toml
cargo test -p iris-ctl --manifest-path tools/Cargo.toml
cargo test -p iris-daemon render_template_json --manifest-path tools/Cargo.toml
cargo test -p iris-daemon iris_control_path_defaults_to_expected_binary --manifest-path tools/Cargo.toml
```

## 종결 상태 요약

| 상태 | 의미 | 항목 수 |
|------|------|---------|
| **Closed** | 코드 + 테스트 + 증거 완비 | 4 |
| **Partial** | 설계·코드 반영 완료, 일부 증거 부족 | 5 |
| **Unverified** | 구조 변경 완료, e2e 동작 회귀 미확인 | 1 |

상세: `docs/executive-closeout.md`

## 읽는 순서

1. 이 README로 아키텍처와 패키지 구성을 파악한다.
2. `docs/executive-closeout.md`로 종결 판정과 잔여 리스크를 확인한다.
3. `docs/evidence-index.md`로 항목별 증거 위치를 추적한다.
4. `docs/detailed-rationale.md`로 핵심 설계 판단을 확인한다.
5. 관심 영역의 소스 코드와 JUnit XML을 직접 대조한다.
