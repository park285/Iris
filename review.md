# Iris 최종 구현본 잔여 이슈 정리 및 수정 가이드

검토 대상: [Iris-review-2026-03-29.tar.gz](sandbox:/mnt/data/Iris-review-2026-03-29.tar.gz)

## 목적

이 문서는 최종 구현본을 기준으로, 이전 리뷰에서 제시했던 기준인 **성능, I/O, 보안, Kotlin-like 구조, 동시성 소유권, 테스트 완결성** 관점에서 **아직 남아 있는 이슈**를 정리하고, 각 항목별로 **왜 문제인지, 어떤 순서로, 어떤 방식으로 고쳐야 하는지**를 실무 문서 형태로 정리한 것이다.

이 문서는 “추가로 예쁘게 다듬을 부분”을 적는 문서가 아니다. 목적은 다음 두 가지다.

1. **운영 리스크를 남기지 않도록 머지 전/후속 PR 우선순위를 명확히 하는 것**
2. **남은 Kotlin-like 전환 과제를 코드 레벨 수정 단위로 떨어뜨리는 것**

---

## 전제: 이번 구현에서 이미 잘된 것

이번 최종 구현은 이전 대비 분명히 올라갔다. 특히 아래는 이미 구조적으로 잘된 축으로 보고, 되돌리면 안 된다.

- 100ms polling hot path를 snapshot diff에서 분리했다.
- snapshot state는 `SnapshotCoordinator`로 상당 부분 이동했다.
- `storage/` 패키지와 typed query/DTO 계층이 생겼다.
- reply lifecycle은 `ReplyLifecycleState` + `ReplyStateMachine`으로 중앙화됐다.
- config는 `ConfigStateStore` / `ConfigPersistence` / `ConfigPolicy`로 분리되기 시작했다.
- durable store는 file rewrite 방식에서 SQLite WAL 방향으로 이동했다.
- secret 역할은 inbound / outbound / bot control로 분리됐다.
- `IrisServer`는 route/module 형태로 분해되었다.

즉 지금 단계는 “리팩터링을 시작할지 말지”가 아니라, **좋아진 구조를 마무리해서 운영형 완성도로 끌어올리는 단계**다.

PR-1 runtime boundary 문서도 hot path 보호와 checkpoint batch flush를 핵심 불변식으로 두고 있고, PR-5a durable storage 문서도 checkpoint journal이 “batched dirty-flush semantics over SQLite WAL”을 가져야 한다고 명시한다. fileciteturn30file3turn30file11

---

## 최종 요약

남은 이슈는 크게 두 종류다.

### A. 반드시 먼저 닫아야 하는 운영 리스크

1. **프로덕션 checkpoint batching이 사실상 꺼져 있음**
2. **webhook outbox expired claim recovery가 시작 시 1회만 수행됨**
3. **full reconcile이 진짜 full이 아니라 seeded-room 재평가에 가까움**
4. **legacy secret migration이 없어 업그레이드 호환성이 약함**

### B. 그 다음 단계의 구조 완성도 이슈

5. **`MemberRepository` 일부 경로에 N+1 lookup 잔여**
6. **`ConfigPolicy`가 실제 동작의 단일 source of truth가 아님**
7. **large-body auth는 streaming hash만 들어갔고 full buffering 제거는 미완료**
8. **nonce cache는 순서는 좋아졌지만 용량/청소 전략이 단순함**
9. **일부 코어는 아직 완전히 Kotlin-like하지 않음**
10. **테스트는 좋아졌지만 몇몇 핵심 테스트가 여전히 flaky/약한 형태임**

권장 우선순위는 아래 순서다.

- **P0**: checkpoint batching, claim recovery cadence, full reconcile completeness, legacy secret migration
- **P1**: MemberRepository 잔여 N+1, ConfigPolicy wiring, RequestBodyReader memory strategy
- **P2**: ReplyAdmissionService ownership 정리, public API value object 확대, nonce cache hardening, 테스트 안정화

---

## 1. P0-1: Checkpoint batching이 프로덕션에서 사실상 비활성화되어 있음

### 현재 상태

설계상 의도는 “cursor advance는 메모리에만 반영하고, flush는 주기적으로만 한다”는 것이다. PR-1 문서는 `CheckpointWriter.flushIfDue()`를 스케줄러가 주기적으로 호출하는 모델을 제시하고 있고, PR-5a 문서도 `SqliteCheckpointJournal`이 batched dirty flush semantics를 가져야 한다고 명시한다. fileciteturn30file3turn30file11

하지만 최종 구현에서는 실제 의미가 다르다.

- `CommandIngressService.checkChange()`는 매 poll 끝에 `checkpointJournal.flushIfDirty()`를 호출한다.
- `SqliteCheckpointJournal.flushIfDirty()`는 dirty가 있으면 **즉시 flush**한다.
- `AppRuntime`는 현재 `SqliteCheckpointJournal`을 직접 주입한다.

즉 실제 동작은 “메모리에 advance” 후 “poll cycle마다 바로 SQLite에 flush”다.

### 왜 문제인가

파일 전체 rewrite보다는 낫지만, 이것은 여전히 **hot path 보호 관점에서는 반쪽짜리 해결**이다.

- poll 주기가 짧을수록 cursor flush frequency가 높아진다.
- checkpoint는 원래 재처리를 조금 허용해도 되는 진행 상태이므로, log 1건/1poll마다 즉시 디스크에 반영할 필요가 없다.
- 테스트가 `BatchedCheckpointJournal` 류의 시간 기반 flush 의미를 검증하고 있다면, 프로덕션과 테스트 의미가 어긋날 수 있다.

### 어떻게 수정해야 하는가

핵심 원칙은 단순하다.

**소비자 API는 그대로 두고, 프로덕션도 batched wrapper를 실제 사용하도록 바꿔야 한다.**

권장 구조:

```kotlin
interface CheckpointJournal : Closeable {
    fun advance(stream: String, cursor: Long)
    fun flushIfDirty()
    fun flushNow()
    fun load(stream: String): Long?
}

class BatchedCheckpointJournal(
    private val delegate: CheckpointJournal,
    private val flushIntervalMs: Long = 5_000,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    private val dirty = AtomicBoolean(false)
    @Volatile private var lastFlushAt: Long = 0L

    override fun advance(stream: String, cursor: Long) {
        delegate.advance(stream, cursor)
        dirty.set(true)
    }

    override fun flushIfDirty() {
        val now = clock()
        if (!dirty.get()) return
        if (now - lastFlushAt < flushIntervalMs) return
        delegate.flushIfDirty()
        lastFlushAt = now
        dirty.set(false)
    }

    override fun flushNow() {
        delegate.flushNow()
        lastFlushAt = clock()
        dirty.set(false)
    }

    override fun load(stream: String): Long? = delegate.load(stream)
    override fun close() = delegate.close()
}
```

### 적용 위치

- `AppRuntime`
  - 기존: `checkpointJournal = SqliteCheckpointJournal(persistenceDriver)`
  - 변경: `checkpointJournal = BatchedCheckpointJournal(SqliteCheckpointJournal(persistenceDriver), flushIntervalMs = 5_000)`
- `CommandIngressService`
  - 현재처럼 `flushIfDirty()`를 poll loop 끝에 호출해도 됨. 실제 flush 여부는 wrapper가 결정.
- `SnapshotObserver`
  - stop/close/shutdown 경로에서는 `flushNow()` 유지

### 검증 테스트

- `advance()` 여러 번 후 `flushIntervalMs` 이전에는 DB 값이 바뀌지 않는지
- `flushIntervalMs` 경과 후 마지막 cursor만 기록되는지
- `shutdown -> flushNow()`에서 최신 cursor가 저장되는지
- 재시작 후 replay 범위가 기대 수준으로만 발생하는지

---

## 2. P0-2: Webhook outbox expired claim recovery가 시작 시 1회뿐임

### 현재 상태

PR-5a 설계는 claim-token lease semantics와 stale claimant 방지를 잘 정의한다. `WebhookDeliveryStore`는 `claimReady`, `markSent`, `markRetry`, `markDead`, `recoverExpiredClaims`를 제공하고, `ClaimedDelivery`는 `claimToken`을 가진다. fileciteturn30file2turn30file11

최종 구현에서도 claim token 검증은 잘 되어 있다. 문제는 cadence다.

- `WebhookOutboxDispatcher.start()`에서 `recoverExpiredClaims(60_000L)`를 한 번 호출하고
- 이후 루프에서는 `pumpReadyEntries()`만 반복한다.

즉 **런타임 도중 만료된 claim은 재회수되지 않는다.**

### 왜 문제인가

네트워크 hang, worker stall, 예상보다 긴 I/O가 생기면 `CLAIMED` 상태가 고착될 수 있다.

- 재시작하면 복구되지만
- 프로세스가 살아 있는 동안은 회수되지 않을 수 있다.

lease 모델은 “claim token”만으로 완성되지 않는다. **만료 lease를 주기적으로 재임대할 수 있어야** 한다.

### 어떻게 수정해야 하는가

가장 단순하고 안전한 방법은 dispatcher loop에 recovery cadence를 넣는 것이다.

```kotlin
private var lastRecoveryAt = 0L
private val recoveryIntervalMs = 30_000L
private val claimTimeoutMs = 60_000L

private suspend fun runLoop() {
    while (isActive) {
        val now = System.currentTimeMillis()
        if (now - lastRecoveryAt >= recoveryIntervalMs) {
            store.recoverExpiredClaims(claimTimeoutMs)
            lastRecoveryAt = now
        }
        pumpReadyEntries()
        delay(pollIntervalMs)
    }
}
```

더 나은 구조는 별도 recovery coroutine을 두는 것이다.

```kotlin
launch {
    while (isActive) {
        store.recoverExpiredClaims(claimTimeoutMs)
        delay(recoveryIntervalMs)
    }
}
```

### 추가 개선

- recovery count metric 남기기
- 특정 route/partition에서 recovery가 과도하게 많으면 warn log
- `claimed_at`를 기준으로 route/attempt count와 함께 진단 payload에 노출

### 검증 테스트

- worker A가 claim 후 멈춤
- timeout 후 recovery 수행
- worker B가 재claim
- stale token을 가진 A의 `markSent/markRetry/markDead`가 무시되는지

PR-5a 테스트 문서도 stale claim overwrite 방지와 recovery를 핵심 시나리오로 둔다. 이 의도를 실제 런타임 cadence까지 완결해야 한다. fileciteturn30file18turn30file1

---

## 3. P0-3: Full reconcile이 완전하지 않음

### 현재 상태

현재 `SnapshotCoordinator.handleFullReconcile()`는 `previousSnapshots.keys`만 다시 dirty로 등록한다. 이건 “이미 seed됐거나 이전에 본 방들”만 재평가한다는 뜻이다.

또 `AppRuntime`의 `RoomSnapshotReader.listRoomChatIds()`는 `memberRepo.listRooms().rooms.map { it.chatId }`를 사용한다. 이 경로는 room id만 필요할 때도 room summary/name resolution을 동반할 수 있다.

### 왜 문제인가

이 구조는 다음 상황에 취약하다.

- 초기 seed 이후 새로 등장한 방
- seed 당시 캐시에 없었던 방
- 현재 실제 room set에는 있으나 `previousSnapshots`에 아직 없는 방

즉 현재 이름과 달리 **진짜 full reconcile이 아니라, known snapshot 재평가**에 가깝다.

### 어떻게 수정해야 하는가

#### 1단계: Full reconcile의 정의를 바로잡기

`handleFullReconcile()`는 반드시 **현재 room source를 다시 조회**해야 한다.

```kotlin
private fun handleFullReconcile() {
    val currentRoomIds = roomSnapshotReader.listRoomChatIds().filter { it > 0L }
    currentRoomIds.forEach(::handleMarkDirty)
    dirtyRoomCountValue.set(dirtyRoomSet.size)
}
```

#### 2단계: room id 조회 경로를 cheap query로 분리

`RoomSnapshotReader.listRoomChatIds()`는 `memberRepo.listRooms()`를 우회해야 한다.

권장 방향:

- `RoomDirectoryQueries.listAllRoomIds()` 추가
- `MemberRepository.listRoomChatIds()` 또는 직접 query service 주입
- title/name/link metadata 조합 없이 `SELECT id FROM chat_rooms ...` 수준으로 읽기

```kotlin
fun listAllRoomIds(): List<Long> =
    sqlClient.query(
        QuerySpec(
            sql = "SELECT id FROM chat_rooms WHERE id > 0 ORDER BY id",
            bindArgs = emptyList(),
            maxRows = 10_000,
            mapper = { row -> row.long("id")!! },
        ),
    )
```

### 검증 테스트

- `previousSnapshots`에 없는 새 방이 full reconcile 후 diff 대상에 들어오는지
- room title/name resolution 없이 id query만 수행되는지
- quiet polling path에는 여전히 snapshot query가 없는지

---

## 4. P0-4: Legacy secret migration이 없음

### 현재 상태

모델 자체는 좋아졌다. `ConfigProvider`는 `inboundSigningSecret`, `outboundWebhookToken`, `botControlToken`을 가진다. `ConfigLayers`와 `ConfigManager`도 새 필드로 이동했다.

문제는 `ConfigSerialization.decodeConfigValues()`가 **legacy endpoint migration만 처리하고, legacy secret migration은 하지 않는다는 점**이다. `ConfigPersistence.load()`는 결국 이 decode 결과를 바로 `toUserConfigState()`로 넘긴다.

즉 과거 설정 파일에 old secret field만 있으면, 업그레이드 후 새 3개 secret field가 비어 있을 가능성이 있다.

### 왜 문제인가

- inbound auth가 예상치 않게 비활성/실패할 수 있음
- outbound webhook token이 비어 있을 수 있음
- bot control path도 동작이 바뀔 수 있음
- 배포 후 “구성은 남아 있는데 인증이 안 된다”는 형태의 운영 사고로 이어질 수 있음

### 어떻게 수정해야 하는가

#### 1단계: legacy secret field를 decode 단계에서 seed

`decodeConfigValues()`에서 raw JSON을 직접 보고 old field를 읽어야 한다.

예시 정책:

- `inboundSigningSecret`가 비어 있으면 `legacyWebhookToken` 사용
- `outboundWebhookToken`가 비어 있으면 `legacyWebhookToken` 사용
- `botControlToken`가 비어 있으면 `legacyBotToken` 사용

```kotlin
private fun migrateLegacySecrets(
    root: JsonObject,
    values: ConfigValues,
): ConfigValues {
    val legacyWebhookToken = root["webhookToken"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()
    val legacyBotToken = root["botToken"]?.jsonPrimitive?.contentOrNull?.trim().orEmpty()

    return values.copy(
        inboundSigningSecret = values.inboundSigningSecret.ifBlank { legacyWebhookToken },
        outboundWebhookToken = values.outboundWebhookToken.ifBlank { legacyWebhookToken },
        botControlToken = values.botControlToken.ifBlank { legacyBotToken },
    )
}
```

그리고 `DecodedConfigValues`에 `migratedLegacySecrets: Boolean`을 추가해 persisted dirty state를 만들도록 한다.

#### 2단계: load/save 로그에 migration 여부를 남김

운영에서 중요한 건 “왜 새 값이 생겼는지” 추적 가능성이다.

#### 3단계: 새 필드만 persist

save 시에는 legacy key를 쓰지 않는다.

### 검증 테스트

- old `webhookToken`만 있는 config -> inbound/outbound 둘 다 seed되는지
- old `botToken`만 있는 config -> botControlToken으로 seed되는지
- old+new 혼재 시 new field가 우선되는지
- save 후에는 legacy field가 다시 기록되지 않는지

---

## 5. P1-1: `MemberRepository` 일부 경로에 N+1 lookup이 남아 있음

### 현재 상태

좋아진 부분은 분명하다.

- snapshot/title path는 `resolveNicknamesBatch()`를 사용한다.
- open chat nickname batch, friend batch, observed profile batch가 존재한다.
- `chat_id = ?` observed profile lookup도 적용됐다.

하지만 non-open API path 일부는 아직 단건 lookup fallback을 탄다.

대표적으로:

- `listMembers()`의 non-open path
- `roomStats()`의 non-open path
- `memberActivity()`의 nickname path
- `resolveNickname()` 자체가 open/friend/observed를 단건 순차 조회

### 왜 문제인가

polling hot path는 많이 좋아졌더라도, `/rooms/*`류 API는 방 인원이 많을수록 DB chatter가 다시 커질 수 있다.

### 어떻게 수정해야 하는가

#### 원칙

**`resolveNickname()`는 최후의 희귀 fallback으로만 남기고, 일반 경로는 전부 `resolveNicknamesBatch()`로 통일**한다.

#### `listMembers()` 수정

현재 non-open path에서 `userIds.map { resolveNickname(...) }`를 하는 대신, 먼저 batch map을 만든다.

```kotlin
val nicknameByUser = resolveNicknamesBatch(userIds, chatId = chatId)

val members = userIds.map { userId ->
    val nickname = nicknameByUser[userId] ?: userId.toString()
    ...
}
```

#### `roomStats()` 수정

open path가 아닌 경우도 batch map 사용:

```kotlin
val nicknameByUser =
    if (linkId != null) {
        ...open batch...
    } else {
        resolveNicknamesBatch(byUser.keys, chatId = chatId)
    }
```

#### `memberActivity()` 수정

단일 조회가 꼭 필요하지 않다면 batch helper 재사용 가능. 단일 API여도 내부는 batch 경로 1건으로 처리하면 구현 일관성이 좋아진다.

### 추가 권장

- `MemberRepository` public API에서 `Long`를 유지하더라도, 내부 local 변수는 `ChatId`, `UserId`, `LinkId` 중심으로 일관성을 높이는 편이 좋다.

### 검증 테스트

- non-open `listMembers()`에서 query count가 인원 수에 따라 선형 증가하지 않는지
- `roomStats()` non-open path query count regression test
- direct/multi/open chat 결과 동치성 유지

---

## 6. P1-2: `ConfigPolicy`가 실제 동작의 source of truth가 아님

### 현재 상태

`ConfigPolicy`는 존재한다.

- `requiresRestart()`
- `validate()`
- `defaultRoutingPolicy`

그리고 `ConfigField`도 secret role별로 분리되어 있다.

그런데 실제 동작 경로는 아직 별도 하드코딩이 남아 있다.

- `applyConfigUpdate()`는 이름별 분기 + 수동 validation
- `pendingRestartFieldNames()`는 bot port만 비교
- `ConfigManager`는 update semantics를 직접 가진다

즉 policy object는 생겼지만, **runtime update/contract response/load validation이 모두 같은 policy를 참조하지 않는다.**

### 왜 문제인가

나중에 restart-required field, validation 규칙, secret field 동작이 바뀌면 수정 지점이 분산된다. 이런 구조는 시간이 지나면 다시 Java-like하게 무거워진다.

### 어떻게 수정해야 하는가

#### 1단계: `applyConfigUpdate()`를 field-aware하게 변경

현재의 name-based imperative branching을 줄이고, field metadata를 `ConfigPolicy`에서 가져오게 한다.

```kotlin
interface ConfigMutationSpec<T> {
    val field: ConfigField
    fun parse(request: ConfigRequest): T
    fun apply(config: ConfigManager, value: T)
}
```

혹은 더 단순하게:

```kotlin
object ConfigPolicy {
    fun requiresRestart(field: ConfigField): Boolean
    fun validate(field: ConfigField, value: Any?): String?
}
```

#### 2단계: `pendingRestartFieldNames()`를 policy 기반 비교로 변경

현재는 bot port만 비교한다. 실제론 secret role field도 restart-required이면 반영되어야 한다.

```kotlin
private val trackedRestartFields = listOf(
    ConfigField.BOT_SOCKET_PORT,
    ConfigField.INBOUND_SIGNING_SECRET,
    ConfigField.OUTBOUND_WEBHOOK_TOKEN,
    ConfigField.BOT_CONTROL_TOKEN,
)
```

#### 3단계: load path validation도 `ConfigPolicy.validate()`를 사용

load, update, contract 모두 같은 policy를 따라야 한다.

### 검증 테스트

- update API와 load API가 같은 validation 결과를 내는지
- pending restart response에 secret fields가 반영되는지
- routing policy가 explicit-empty로 계속 표기되는지

---

## 7. P1-3: large-body auth는 부분 해결만 됨

### 현재 상태

`RequestBodyReader`는 읽는 동안 SHA-256 digest를 계산한다. 이건 좋다. 예전처럼 “다 읽고 다시 해시”라는 이중 패스는 줄었다.

하지만 구현은 여전히 `ByteArrayOutputStream`에 전부 쌓고, 마지막에 `ByteArray` → `String`으로 바꾼다.

즉 **streaming digest는 들어갔지만, full buffering은 그대로**다.

### 왜 문제인가

48MB class request를 계속 문자열로 다루는 경로는 여전히 CPU/메모리 압박이 크다.

### 어떻게 수정해야 하는가

#### 선택지 A: staged upload

가장 깔끔하다.

- `/upload-temp-image`에서 파일 저장 후 handle 반환
- `/reply-image`는 handle만 받음
- control path와 upload path 분리

장점: 본질 해결
단점: API 변경 큼

#### 선택지 B: temp file spill

현 구조를 덜 바꾸는 절충안이다.

- 작은 body는 메모리 buffer
- threshold 초과 시 temp file로 spill
- digest는 계속 streaming
- JSON parse는 spill 결과에서 수행

```kotlin
if (totalRead <= memoryThreshold) {
    memoryOutput.write(...)
} else {
    fileOutput.write(...)
}
```

#### 선택지 C: route별 전략 분리

- `/reply` text/json 경로는 memory
- `/reply-image`만 file-spill 또는 staged upload

### 추천

운영 부담을 고려하면 **B 또는 C**가 Phase 2.5로 적절하다.

### 검증 테스트

- threshold 이하 body는 memory path
- threshold 초과 body는 temp file path
- digest correctness 유지
- too-large reject는 여전히 early reject

---

## 8. P1-4: nonce cache hardening이 필요함

### 현재 상태

좋아진 점:

- signature 검증 후 nonce 등록

남은 문제:

- `ConcurrentHashMap` 무제한 성장 가능
- 매 요청마다 `removeIf` 기반 purge
- 요청량이 많으면 O(n) 청소 비용

### 어떻게 수정해야 하는가

#### 1단계: 최대 크기 제한 추가

```kotlin
private val maxNonceEntries = 10_000
```

#### 2단계: purge cadence 제한

매 요청마다 purge하지 말고, 일정 시간 간격으로만 purge한다.

```kotlin
@Volatile private var lastPurgeAt = 0L

private fun maybePurge(now: Long) {
    if (now - lastPurgeAt < 5_000) return
    lastPurgeAt = now
    purgeExpiredNonces(now)
}
```

#### 3단계: over-capacity 시 soft reject 또는 oldest-ish cleanup

정밀 LRU까지는 과할 수 있지만, 최소한 보호 장치는 필요하다.

### 검증 테스트

- invalid signature는 여전히 nonce를 소비하지 않는지
- 많은 nonce 삽입 후 map size가 상한을 넘지 않는지
- purge cadence가 줄어도 재사용 nonce는 막는지

---

## 9. P2-1: 남은 Kotlin-like 과제

여기서 말하는 Kotlin-like는 문법이 아니라 **상태 소유권, 타입 경계, 책임 분리**다.

### 9-1. `ReplyAdmissionService`는 아직 old-school concurrency 냄새가 남음

현재 `ReplyAdmissionService`는 다음을 섞는다.

- `ConcurrentHashMap`
- `@Synchronized`
- worker lifecycle state
- channel worker

이 구조는 동작은 하지만, “누가 registry를 단독 소유하는가”가 아주 선명하지는 않다.

#### 권장 수정

장기적으로는 `ReplyAdmissionCoordinator` actor로 바꾸는 것이 가장 좋다.

```kotlin
sealed interface AdmissionCommand
object Start : AdmissionCommand
object Shutdown : AdmissionCommand
data class Enqueue(val key: ReplyQueueKey, val request: PipelineRequest, val reply: CompletableDeferred<ReplyAdmissionResult>) : AdmissionCommand
```

지금 당장 큰 전환이 부담되면 최소한 아래는 하자.

- `workerRegistry` 접근 경로를 더 줄이기
- `started/shutdownComplete`를 state enum으로 묶기
- worker creation/removal을 한 mutex/monitor 아래로 정리

### 9-2. public API는 여전히 raw `Long` 중심

storage 내부는 `ChatId`, `UserId`, `LinkId`가 생겼지만,

- `MemberRepository`
- `ReplyCommand`
- `RoomSnapshotAssembler` 일부 경계

는 여전히 raw Long 중심이다.

이건 반드시 지금 바로 바꿀 필요는 없지만, 다음 리팩터링 대상은 맞다.

#### 권장 방향

public API는 호환성을 위해 `Long` 유지 가능. 대신 내부에서는 가능한 빨리 value object로 승격.

### 9-3. `AppRuntime`가 composition root로서 점점 커지고 있음

이건 자연스러운 면도 있지만, 지금처럼 reply/config/storage/snapshot/web wiring이 한 파일에 계속 모이면 다시 거대 파일이 될 수 있다.

#### 권장 수정

- `RuntimeBuilders.kt`
- `ReplyRuntimeFactory.kt`
- `PersistenceFactory.kt`
- `SnapshotRuntimeFactory.kt`

정도로 builder를 뽑아 `AppRuntime`는 조립 순서만 남기기

---

## 10. P2-2: 테스트 품질 보강

### 현재 상태

테스트는 확실히 많아졌고, stale claim / reply transition / snapshot coordinator 같은 핵심 테스트는 좋아졌다.

하지만 일부는 아직 약하다.

- `Thread.sleep(...)` 의존
- source contains 기반 wiring test
- production semantics와 wrapper semantics가 어긋날 수 있는 checkpoint 테스트

### 어떻게 수정해야 하는가

#### 1단계: coroutine test scheduler 사용

`runTest`, `advanceTimeBy`, `advanceUntilIdle` 중심으로 변경

#### 2단계: wiring test는 source text가 아니라 실제 객체 graph/behavior 기반으로 변경

#### 3단계: file-backed SQLite durability test 추가

in-memory SQLite는 crash durability 증거가 아니다.

### 꼭 추가할 테스트

- batched checkpoint production wiring test
- periodic claim recovery integration test
- full reconcile catches newly appeared room
- legacy secret migration round-trip test
- non-open listMembers query count regression test
- request body temp-file spill test

---

## 11. 권장 실행 순서

### 즉시 착수 (P0)

1. **BatchedCheckpointJournal를 프로덕션에 실제 적용**
2. **WebhookOutboxDispatcher에 periodic recoverExpiredClaims 추가**
3. **SnapshotCoordinator full reconcile을 current room source 기반으로 수정**
4. **legacy secret migration 추가**

### 바로 다음 (P1)

5. `MemberRepository` non-open API path batch화
6. `ConfigPolicy`를 update/contract/load path의 source of truth로 통일
7. `RequestBodyReader` file-spill 또는 staged upload 전략 도입
8. `RequestAuthenticator` nonce cache hardening

### 후속 구조 다듬기 (P2)

9. `ReplyAdmissionService` ownership 정리
10. value object 경계 확대
11. `AppRuntime` builder/factory 분리
12. flaky/weak test 정리

---

## 12. 최종 완료 기준

아래를 만족해야 “남은 코틀린라이크 + 운영 리스크”가 실질적으로 닫혔다고 볼 수 있다.

- 100ms polling path에서 snapshot query와 지속적 durable flush가 발생하지 않는다.
- checkpoint는 실제 프로덕션에서도 batched dirty-flush semantics를 가진다.
- expired claim은 런타임 중 주기적으로 회수된다.
- full reconcile은 현재 존재하는 전체 room set을 기준으로 동작한다.
- legacy secret config에서 업그레이드해도 인증/웹훅이 깨지지 않는다.
- non-open member/stats API는 더 이상 per-user nickname lookup에 의존하지 않는다.
- ConfigPolicy는 load/update/contract의 공통 source of truth가 된다.
- large-body auth path는 digest만 streaming이 아니라 메모리 전략까지 분리된다.
- nonce cache는 무제한 성장하지 않는다.
- 남은 concurrency 중심 서비스는 ownership이 더 명확해진다.
- 주요 시간 의존 테스트가 scheduler 기반으로 안정화된다.

---

## 결론

현재 구현은 이미 많이 좋아졌고, 리팩터링 자체는 성공적이다. 다만 지금 남은 문제들은 더 이상 “취향”의 영역이 아니다.

특히 아래 네 가지는 운영 투입 전 마지막 필수 보강에 가깝다.

1. checkpoint batching의 실제 적용
2. periodic claim recovery
3. full reconcile completeness
4. legacy secret migration

이 네 가지만 먼저 닫아도, 이번 구현은 “좋은 구조”를 넘어 **실운영에 올리기 위한 마지막 방어선까지 갖춘 구조**로 올라간다.

그 다음 단계에서 N+1 잔여, ConfigPolicy wiring, large-body memory strategy, nonce cache, 남은 Kotlin-like ownership을 정리하면 된다.
