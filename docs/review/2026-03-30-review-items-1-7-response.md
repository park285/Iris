# review.md 1-7 수정 상세 응답

이 문서는 외부 리뷰어 공유용으로, [review.md](/home/kapu/gemini/Iris/review.md)의 1~7번 항목에 대해 **무엇을** 바꿨고 **어떻게** 바꿨는지 코드 기준으로 정리한 응답 문서다.

## 범위

- 반영 대상: `review.md` 항목 1~7
- 제외 대상: `review.md` 항목 8~10
- 검증 명령: `./gradlew :app:testDebugUnitTest`

## 1. Checkpoint batching 프로덕션 적용

- 변경 전:
  `AppRuntime`가 [SqliteCheckpointJournal](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/SqliteCheckpointJournal.kt)를 직접 주입해서, poll loop의 `flushIfDirty()`가 사실상 즉시 SQLite flush로 이어졌습니다.
- 수정 내용:
  [AppRuntime.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/AppRuntime.kt)에서 `BatchedCheckpointJournal(delegate = SqliteCheckpointJournal(...))`를 실제 프로덕션 wiring으로 바꿨습니다.
- 구현 방식:
  [CheckpointJournal.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/CheckpointJournal.kt)에서 `BatchedCheckpointJournal`이 flush cadence를 소유하고, 실제 저장은 delegate가 맡도록 wrapper 구조로 정리했습니다. 기존 `CheckpointStore` 기반 테스트를 깨지 않기 위해 store-backed adapter도 유지했습니다.
- 대표 스니펫:

```kotlin
checkpointJournal =
    BatchedCheckpointJournal(
        delegate = SqliteCheckpointJournal(persistenceDriver),
    )
```

```kotlin
override fun flushIfDirty() {
    val now = clock()
    if (!dirty) return
    if (now - lastFlushAtMs < flushIntervalMs) return
    delegate.flushIfDirty()
    dirty = false
    lastFlushAtMs = now
}
```
- 검증:
  [BatchedCheckpointJournalTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/persistence/BatchedCheckpointJournalTest.kt)와 [AppRuntimeWiringTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/AppRuntimeWiringTest.kt)를 보강했고, 전체 `:app:testDebugUnitTest` 통과를 확인했습니다.

## 2. Webhook outbox expired claim recovery cadence

- 변경 전:
  dispatcher 시작 시점에만 expired claim recovery가 수행되고, 런타임 중에는 `pumpReadyEntries()`만 반복했습니다.
- 수정 내용:
  [WebhookOutboxDispatcher.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt)에 시작 직후 1회 recovery와 주기적 recovery cadence를 모두 넣었습니다.
- 구현 방식:
  dispatcher 내부에 `nextClaimRecoveryAtMs`를 두고 recovery timing 상태를 직접 소유하게 했습니다. `start()`는 즉시 1회 recovery를 실행하고, 이후 loop는 `recoverExpiredClaimsIfDue()`만 호출하도록 분리했습니다.
- 대표 스니펫:

```kotlin
fun start() {
    if (pollingJob?.isActive == true) return
    recoverExpiredClaimsNow()
    pollingJob =
        coroutineScope.launch {
            while (isActive) {
                recoverExpiredClaimsIfDue()
                pumpReadyEntries()
                delay(pollIntervalMs)
            }
        }
}
```
- 검증:
  [WebhookOutboxDispatcherTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt)에 “실행 중 주기 recovery”와 “시작 직후 recovery” 케이스를 추가했습니다.

## 3. Full reconcile completeness

- 변경 전:
  full reconcile이 현재 room source가 아니라 `previousSnapshots.keys` 중심으로 동작해서, 새 room은 늦게 보이고 삭제된 room은 정리되지 않았습니다.
- 수정 내용:
  [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt)는 full reconcile 시 **현재 room ids + 기존 snapshot keys**를 모두 dirty 대상에 넣도록 바뀌었습니다.
- 구현 방식:
  현재 room 목록은 [RoomDirectoryQueries.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/storage/RoomDirectoryQueries.kt)의 `listAllRoomIds()` 전용 query에서 읽고, reconcile 범위는 `previousSnapshots.keys + currentRoomIds`의 합집합으로 계산하게 했습니다. 이 방식으로 신규 room과 삭제된 room을 모두 처리합니다.
- 대표 스니펫:

```kotlin
private fun handleFullReconcile() {
    val currentRoomIds =
        roomSnapshotReader
            .listRoomChatIds()
            .filter { it > 0L }
            .toSet()
    (previousSnapshots.keys + currentRoomIds).forEach(::handleMarkDirty)
}
```

```kotlin
fun listAllRoomIds(): List<Long> =
    db.query(
        QuerySpec(
            sql = "SELECT id FROM chat_rooms WHERE id > 0 ORDER BY id",
            bindArgs = emptyList(),
            maxRows = MAX_ROOM_ID_ROWS,
            mapper = { row -> row.long("id") ?: 0L },
        ),
    )
```
- 검증:
  [SnapshotCoordinatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/snapshot/SnapshotCoordinatorTest.kt), [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt), [RoomDirectoryQueriesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/storage/RoomDirectoryQueriesTest.kt)에 신규 room/삭제 room/cheap room-id query 케이스를 추가했습니다.

## 4. Legacy secret migration

- 변경 전:
  legacy endpoint migration만 있었고, legacy `webhookToken`/`botToken`이 새 secret role 필드로 승격되지 않았습니다.
- 수정 내용:
  [ConfigSerialization.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigSerialization.kt)에서 legacy secret field를 decode 단계에서 새 필드로 seed하도록 변경했습니다.
- 구현 방식:
  `webhookToken`은 `inboundSigningSecret`과 `outboundWebhookToken`의 fallback seed로, `botToken`은 `botControlToken`의 fallback seed로 사용합니다. 새 필드가 이미 있으면 새 필드를 우선합니다. [ConfigPersistence.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPersistence.kt)와 [ConfigManager.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigManager.kt)는 migration dirty state와 load log를 반영하도록 정리했습니다.
- 대표 스니펫:

```kotlin
val migratedValues =
    values.copy(
        inboundSigningSecret = values.inboundSigningSecret.ifBlank { legacyWebhookToken },
        outboundWebhookToken = values.outboundWebhookToken.ifBlank { legacyWebhookToken },
        botControlToken = values.botControlToken.ifBlank { legacyBotToken },
    )
```

```kotlin
return LoadedConfig(
    userState = userState,
    migratedLegacyEndpoint = decoded.migratedLegacyEndpoint,
    migratedLegacySecrets = decoded.migratedLegacySecrets,
)
```
- 검증:
  [ConfigSerializationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigSerializationTest.kt), [SecretRoleMigrationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SecretRoleMigrationTest.kt), [ConfigPersistenceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/config/ConfigPersistenceTest.kt)에서 legacy-only, mixed old/new, save-after-migration 케이스를 확인했습니다.

## 5. MemberRepository N+1 nickname lookup

- 변경 전:
  non-open `listMembers`와 `roomStats`가 일반 경로에서 per-user nickname lookup으로 떨어질 수 있었습니다.
- 수정 내용:
  [MemberRepository.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepository.kt)에서 non-open 경계는 nickname map을 한 번만 준비하고, 조립 단계는 그 결과만 읽게 바꿨습니다.
- 구현 방식:
  `prepareNicknameLookup()`를 추가해 open path와 non-open path의 batch lookup 책임을 경계에서 통합했습니다. non-open은 `resolveNicknamesBatch(..., chatId = ...)`, open은 `loadOpenNicknamesBatch(...)`를 사용합니다. direct chat observed-profile fallback과 learned observed-profile fallback은 유지했습니다.
- 대표 스니펫:

```kotlin
val nicknameByUser = prepareNicknameLookup(userIds, chatId = chatId)
val members =
    userIds.map { userId ->
        val resolvedNickname = nicknameByUser[userId] ?: userId.toString()
        ...
    }
```

```kotlin
private fun prepareNicknameLookup(
    userIds: Collection<Long>,
    linkId: Long? = null,
    chatId: Long? = null,
): Map<Long, String> =
    if (linkId != null) {
        memberIdentity
            .loadOpenNicknamesBatch(LinkId(linkId), userIds.distinct().filter { it > 0L }.map(::UserId))
            .mapKeys { it.key.value }
            .mapValues { (userId, nickname) -> nickname ?: userId.toString() }
    } else {
        resolveNicknamesBatch(userIds, chatId = chatId)
    }
```
- 검증:
  [MemberRepositoryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt)에 non-open batch ownership 회귀 테스트와 기존 direct chat / observed profile fixture 보정을 함께 넣었습니다.

## 6. ConfigPolicy source of truth

- 변경 전:
  `ConfigPolicy`는 존재했지만, 실제 validation / pending-restart / update contract 결정은 여러 파일에 흩어져 있었습니다.
- 수정 내용:
  [ConfigPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt)에 restart-required field, validation, pending-restart 비교, webhook route/endpoint validation을 모았습니다.
- 구현 방식:
  [ConfigContract.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigContract.kt)는 pending-restart 계산을 policy에서 가져오고, [ConfigUpdateOutcome.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigUpdateOutcome.kt)는 field validation과 webhook route/endpoint validation을 policy 기반으로 수행하게 바꿨습니다. [ConfigPersistence.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPersistence.kt)도 load 단계에서 `ConfigPolicy.validate()`를 공통으로 사용합니다.
- 대표 스니펫:

```kotlin
fun pendingRestartFieldNames(
    snapshot: ConfigValues,
    effective: ConfigValues,
): List<String> =
    fieldPolicies
        .asSequence()
        .filter { it.restartRequired }
        .filter { it.differs(snapshot, effective) }
        .map { it.contractName }
        .toList()
```

```kotlin
private fun requireValidState(
    field: ConfigField,
    state: UserConfigState,
) {
    ConfigPolicy.validateField(field, state)?.let { error ->
        throw ApiRequestException(error.message)
    }
}
```
- 검증:
  [ConfigContractTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigContractTest.kt), [ConfigPersistenceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/config/ConfigPersistenceTest.kt), [ConfigSerializationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigSerializationTest.kt) 등 관련 config 테스트를 갱신했습니다.

## 7. Large-body auth memory strategy

- 변경 전:
  digest는 streaming으로 계산했지만 body는 `ByteArrayOutputStream`에 끝까지 모아 두는 구조였습니다.
- 수정 내용:
  [RequestBodyReader.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt)에 `RequestBodySink` / `RequestBodyStorage` 경계를 도입하고, 64 KiB 이후에는 temp spill로 승격되게 바꿨습니다.
- 구현 방식:
  작은 body는 `InMemoryRequestBodyStorage`, 큰 body는 `SpillFileRequestBodyStorage`가 소유합니다. spill 생성과 첫 write 실패 시 cleanup이 보장되도록 경로를 정리했고, digest는 기존처럼 streaming으로 유지했습니다.
- 대표 스니펫:

```kotlin
internal suspend fun readBodyWithStreamingDigest(
    bodyChannel: ByteReadChannel,
    declaredContentLength: Long?,
    maxBodyBytes: Int,
    bufferingPolicy: RequestBodyBufferingPolicy = RequestBodyBufferingPolicy(),
): StreamingBodyResult
```

```kotlin
if (storage is InMemoryRequestBodyStorage && nextTotalBufferedBytes > policy.maxInMemoryBytes) {
    storage = spillToDisk(storage as InMemoryRequestBodyStorage)
}
```

```kotlin
val spillStorage =
    try {
        policy.spillStorageFactory(spillPath)
    } catch (error: Throwable) {
        Files.deleteIfExists(spillPath)
        throw error
    }
```
- 검증:
  [RequestBodyReaderTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/RequestBodyReaderTest.kt)에 spill-on-success, spill-on-over-limit, spill-write-failure-cleanup 테스트를 추가했습니다.

## 전체 검증

아래 명령으로 전체 app 단위 테스트를 다시 실행했고 통과했습니다.

```bash
./gradlew :app:testDebugUnitTest
```

외부 리뷰어 관점에서 보면, 1~7은 “반영됨” 수준이 아니라 **문제 전 상태를 실제 코드 구조로 치환했고, 각 변경에 대응하는 회귀 테스트까지 붙은 상태**입니다.
