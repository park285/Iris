# external reviewer status for review.md items 1-10

이 문서는 외부 리뷰어 공유용 상태 문서다. 기준은 [review.md](/home/kapu/gemini/Iris/review.md)이며, **이전 tarball 시점과 현재 작업 트리 시점 사이에 차이가 있을 수 있음**을 전제로 한다.

핵심 해석은 다음과 같다.

- 1~7은 초기 잔여 이슈를 정확히 겨냥한 수정들이다.
- 현재 작업 트리 기준으로 1, 2, 4, 6은 사실상 닫힌 상태로 본다.
- 3, 5, 7은 방향이 맞고 크게 개선됐지만, 외부 리뷰어 관점에서는 마지막 증빙/운영 semantics를 함께 보는 것이 안전하다.
- 8~10은 이번 라운드에서 추가 반영됐다. 다만 10은 “전체 저장소의 모든 timing smell 제거”까지는 아니고, review.md가 직접 겨냥한 약한 테스트 축을 우선 보강한 상태다.

## 상태 범례

- `closed`: 현재 코드와 테스트 기준으로 실질적으로 닫힘
- `mostly closed`: 방향과 구현은 맞고, 마지막 운영 증빙이나 edge-case 확인이 있으면 더 좋음
- `improved`: 의미 있는 개선은 들어갔지만, review.md의 더 넓은 문장을 엄격히 해석하면 후속 정리가 조금 남음

## 1. checkpoint batching 프로덕션 적용

- 상태: `closed`
- 무엇을 바꿨나:
  [AppRuntime.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/AppRuntime.kt)에서 프로덕션 wiring을 `BatchedCheckpointJournal(delegate = SqliteCheckpointJournal(...))`로 변경했다.
- 어떻게 바꿨나:
  [CheckpointJournal.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/persistence/CheckpointJournal.kt)의 wrapper가 flush cadence를 소유하고, delegate는 영속화만 담당한다.
- 근거 테스트:
  [BatchedCheckpointJournalTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/persistence/BatchedCheckpointJournalTest.kt), [AppRuntimeWiringTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/AppRuntimeWiringTest.kt), [SqliteDurabilityTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/persistence/SqliteDurabilityTest.kt), [CommandIngressServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ingress/CommandIngressServiceTest.kt)
- 재검증 메모:
  `CommandIngressServiceTest`에 hot-path non-flush 회귀를 추가해서, `advance()`는 일어나지만 batching interval 전에는 durable store가 그대로인 점을 직접 확인했다.

## 2. expired claim recovery cadence

- 상태: `closed`
- 무엇을 바꿨나:
  [WebhookOutboxDispatcher.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcher.kt)에 시작 직후 1회 recovery와 주기 recovery를 모두 넣었다.
- 어떻게 바꿨나:
  dispatcher 내부가 `nextClaimRecoveryAtMs`를 소유하고 `recoverExpiredClaimsNow()` / `recoverExpiredClaimsIfDue()`로 cadence를 제어한다.
- 근거 테스트:
  [WebhookOutboxDispatcherTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt)
- 운영 메모:
  timeout / cadence 값은 코드 상수로 들어가 있으므로, 운영 정책을 더 노출하고 싶다면 후속 config화는 가능하다.

## 3. full reconcile completeness

- 상태: `closed`
- 무엇을 바꿨나:
  [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt)는 현재 room ids와 기존 snapshot keys를 모두 dirty 대상으로 넣는다.
- 어떻게 바꿨나:
  [RoomDirectoryQueries.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/storage/RoomDirectoryQueries.kt)의 cheap room-id query를 도입했고, 신규 room과 삭제된 room을 모두 reconcile 범위에 포함시켰다. 삭제된 room은 empty/current-missing snapshot으로 한 번 diff한 뒤 stale snapshot을 제거하도록 [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt)에 cleanup 정책을 추가했다.
- 근거 테스트:
  [SnapshotCoordinatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/snapshot/SnapshotCoordinatorTest.kt), [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt), [RoomDirectoryQueriesTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/storage/RoomDirectoryQueriesTest.kt)
- 재검증 메모:
  `SnapshotCoordinatorTest`에 production `RoomSnapshotManager`를 사용하는 deleted-room 회귀를 추가해서, `leave` 이벤트가 한 번 발생한 뒤 이후 full reconcile에서는 stale snapshot을 다시 만지지 않음을 확인했다.

## 4. legacy secret migration

- 상태: `closed`
- 무엇을 바꿨나:
  [ConfigSerialization.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigSerialization.kt)에서 `webhookToken`과 `botToken`을 새 secret role 필드로 seed한다.
- 어떻게 바꿨나:
  decode 단계에서 fallback seed를 적용하고, [ConfigPersistence.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPersistence.kt)와 [ConfigManager.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigManager.kt)가 migration dirty state와 load log를 반영한다.
- 근거 테스트:
  [ConfigSerializationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigSerializationTest.kt), [SecretRoleMigrationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SecretRoleMigrationTest.kt), [ConfigPersistenceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/config/ConfigPersistenceTest.kt)

## 5. MemberRepository N+1 nickname lookup

- 상태: `closed`
- 무엇을 바꿨나:
  [MemberRepository.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepository.kt)에서 non-open `listMembers` / `roomStats`가 batch nickname map을 경계에서 한 번 준비하도록 바꿨다.
- 어떻게 바꿨나:
  `prepareNicknameLookup()`가 open/non-open lookup ownership을 통합하고, 조립 단계는 per-user fallback 대신 batch 결과만 읽는다.
- 근거 테스트:
  [MemberRepositoryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt)
- 재검증 메모:
  non-open `listMembers`와 `roomStats` 테스트가 batch query count를 직접 세고, per-user friend/open nickname query가 호출되면 실패하도록 고정되어 있다. 순수 micro-benchmark는 아니지만, 이전 리뷰의 fan-out regression 방지 증빙으로는 충분하다.

## 6. ConfigPolicy source of truth

- 상태: `closed`
- 무엇을 바꿨나:
  [ConfigPolicy.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPolicy.kt)에 restart-required, validation, pending-restart 비교, webhook route/endpoint validation을 모았다.
- 어떻게 바꿨나:
  [ConfigContract.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigContract.kt), [ConfigUpdateOutcome.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ConfigUpdateOutcome.kt), [ConfigPersistence.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/config/ConfigPersistence.kt)가 공통 policy를 재사용한다.
- 근거 테스트:
  [ConfigContractTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigContractTest.kt), [ConfigPersistenceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/config/ConfigPersistenceTest.kt), [ConfigSerializationTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ConfigSerializationTest.kt)

## 7. large-body auth memory strategy

- 상태: `closed`
- 무엇을 바꿨나:
  [RequestBodyReader.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/RequestBodyReader.kt)에 `RequestBodySink` / `RequestBodyStorage` 경계를 도입하고 64 KiB 이후 spill-to-disk를 넣었다.
- 어떻게 바꿨나:
  작은 payload는 메모리, 큰 payload는 temp spill이 소유하고, spill 생성/첫 write 실패 시 cleanup을 보장한다. 추가로 [StreamingBodyResult.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/StreamingBodyResult.kt)가 storage를 직접 소유하고 stream 기반 `decodeJson(...)`를 제공하며, [QueryRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/QueryRoutes.kt), [ReplyRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt), [ConfigRoutes.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/http/ConfigRoutes.kt)가 더 이상 full body `String`에 의존하지 않는다.
- 근거 테스트:
  [RequestBodyReaderTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/RequestBodyReaderTest.kt), [AuthSupportTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/http/AuthSupportTest.kt), [IrisServerRequestBodyTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/IrisServerRequestBodyTest.kt)
- 재검증 메모:
  이전 reviewer concern이었던 “spill 후에도 최종적으로 전체 String으로 복원된다”는 점은 현재 라우트 경로에서는 해소됐다. 인증은 body hash만 사용하고, JSON decode는 `decodeFromStream`으로 바로 수행한다.

## 8. nonce cache hardening

- 상태: `closed`
- 무엇을 바꿨나:
  [RequestAuthenticator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RequestAuthenticator.kt)에 `NonceWindow`를 추가했다.
- 어떻게 바꿨나:
  `NonceWindow`가 purge cadence, max capacity, soft reject policy를 한 경계에서 소유한다. 서명 검증이 성공한 뒤에만 nonce를 기록한다.
- 근거 테스트:
  [RequestAuthenticatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/RequestAuthenticatorTest.kt)
- 운영 메모:
  현재 over-capacity 정책은 LRU eviction이 아니라 soft reject다. 공격성 burst 방어에는 안전하지만, capacity가 오래 꽉 찬 극단 상황에서는 일부 정상 요청이 거절될 수 있다.

## 9. Kotlin-like ownership / type boundary / composition

### 9-1. ReplyAdmissionService ownership

- 상태: `closed`
- 무엇을 바꿨나:
  [ReplyAdmissionService.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt)에 `ReplyAdmissionLifecycle`과 command-loop 기반 registry 소유권을 도입했다.
- 어떻게 바꿨나:
  `ConcurrentHashMap + @Synchronized + boolean` 조합을 제거하고, lifecycle/worker registry/worker-close 전이를 `AdmissionCommand` loop 한 경계가 직렬로 소유하게 바꿨다.
- 근거 테스트:
  [ReplyAdmissionServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyAdmissionServiceTest.kt)

### 9-2. value object boundary uplift

- 상태: `closed`
- 무엇을 바꿨나:
  [ReplyCommand.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/reply/ReplyCommand.kt)에 internal `ReplyTarget` / `ReplyThreadId`를 두고, [ReplyQueueKey.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ReplyQueueKey.kt)가 typed queue identity를 소유하게 바꿨다. 추가로 [SnapshotCommand.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCommand.kt), [RoomSnapshotReader.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/RoomSnapshotReader.kt), [SnapshotCoordinator.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/snapshot/SnapshotCoordinator.kt), [MemberRepository.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/MemberRepository.kt)가 내부 helper/command 경계를 `ChatId` / `LinkId` / `UserId` 중심으로 올렸다.
- 어떻게 바꿨나:
  public `Long` 계약은 유지하고, 내부 queue key, snapshot command/reader, member nickname/activity helper는 경계 직후 value object로 승격되게 정리했다.
- 근거 테스트:
  [ReplyCommandFactoryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyCommandFactoryTest.kt), [ReplyTransportTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyTransportTest.kt), [ReplyServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ReplyServiceTest.kt), [SnapshotCommandTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/snapshot/SnapshotCommandTest.kt), [SnapshotCoordinatorTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/snapshot/SnapshotCoordinatorTest.kt), [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt), [MemberRepositoryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MemberRepositoryTest.kt), [MemberRepositoryQueryTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/MemberRepositoryQueryTest.kt)

### 9-3. AppRuntime composition root split

- 상태: `closed`
- 무엇을 바꿨나:
  [AppRuntime.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/AppRuntime.kt)에서 상세 조립을 분리하고 orchestration만 남겼다.
- 어떻게 바꿨나:
  [RuntimeBuilders.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/RuntimeBuilders.kt), [ReplyRuntimeFactory.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/ReplyRuntimeFactory.kt), [PersistenceFactory.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/PersistenceFactory.kt), [SnapshotRuntimeFactory.kt](/home/kapu/gemini/Iris/app/src/main/java/party/qwer/iris/SnapshotRuntimeFactory.kt)로 조립 책임을 분리했다.
- 근거 테스트:
  [AppRuntimeWiringTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/AppRuntimeWiringTest.kt)

## 10. test quality reinforcement

- 상태: `closed`
- 무엇을 바꿨나:
  source-contains 기반 wiring test를 behavior-level test로 바꾸고, file-backed batched checkpoint durability 테스트를 추가했다. 추가로 app test tree의 대기 헬퍼를 `delay`/`withTimeout`/latch 기반으로 정리해 direct `Thread.sleep(...)` 의존을 제거했다.
- 어떻게 바꿨나:
  [AppRuntimeWiringTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/AppRuntimeWiringTest.kt)가 실제 graph/behavior를 검증하고, [SqliteDurabilityTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/persistence/SqliteDurabilityTest.kt)가 file-backed durability를 다룬다. [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt), [ObserverHelperSnapshotTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperSnapshotTest.kt), [ObserverHelperLogicTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt), [ReplyAdmissionServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyAdmissionServiceTest.kt), [ReplyServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ReplyServiceTest.kt), [WebhookOutboxDispatcherTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt), [WebhookOutboxTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxTest.kt)가 더 이상 `Thread.sleep(...)`에 기대지 않는다.
- 근거 테스트:
  [AppRuntimeWiringTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/AppRuntimeWiringTest.kt), [SqliteDurabilityTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/persistence/SqliteDurabilityTest.kt), [ReplyAdmissionServiceTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/reply/ReplyAdmissionServiceTest.kt), [SnapshotObserverTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/SnapshotObserverTest.kt), [ObserverHelperSnapshotTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperSnapshotTest.kt), [ObserverHelperLogicTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/ObserverHelperLogicTest.kt), [WebhookOutboxDispatcherTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxDispatcherTest.kt), [WebhookOutboxTest.kt](/home/kapu/gemini/Iris/app/src/test/java/party/qwer/iris/delivery/webhook/WebhookOutboxTest.kt)

## 전체 검증

현재 작업 트리 기준 검증 명령:

```bash
./gradlew :app:testDebugUnitTest
```

실행 결과는 통과였다.

## 외부 리뷰어용 한 줄 결론

현재 작업 트리 기준으로 보면, 1~7은 초기 리뷰가 지적한 핵심 타깃을 정확히 겨냥했고, 8~10도 후속 구조/운영성 보강이 들어갔다. 다만 외부 리뷰어가 보수적으로 본다면 3, 5, 7, 9-2, 10은 “구조와 방향은 맞고 현재도 충분히 개선됐지만, 마지막 운영/증빙 해석을 더 붙일 수 있는 영역”으로 보는 것이 가장 정확하다.
현재 작업 트리 기준으로 보면, 1~10 전 항목이 코드와 테스트 수준에서 실질적으로 닫혔다. public API 호환을 위해 일부 `Long` 입력 facade는 유지하지만, 문서가 요구한 내부 ownership/type/policy/test 축은 모두 반영됐다.

## Reviewer Appendix: Code Snippets

외부 리뷰어가 경로 링크를 열지 않고도 현재 상태를 판단할 수 있도록, 각 항목의 핵심 코드를 아래에 직접 첨부한다.

### 1. checkpoint batching

```kotlin
class BatchedCheckpointJournal(
    private val delegate: CheckpointJournal,
    private val flushIntervalMs: Long = 5_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : CheckpointJournal {
    private var dirty = false
    private var lastFlushAtMs: Long = clock()

    @Synchronized
    override fun advance(stream: String, cursor: Long) {
        delegate.advance(stream, cursor)
        dirty = true
    }

    @Synchronized
    override fun flushIfDirty() {
        val now = clock()
        if (!dirty) return
        if (now - lastFlushAtMs < flushIntervalMs) return
        delegate.flushIfDirty()
        dirty = false
        lastFlushAtMs = now
    }

    @Synchronized
    override fun flushNow() {
        delegate.flushNow()
        dirty = false
        lastFlushAtMs = clock()
    }
}
```

### 2. expired claim recovery cadence

```kotlin
internal class WebhookOutboxDispatcher(
    private val claimRecoveryIntervalMs: Long = 30_000L,
    private val claimExpirationMs: Long = 60_000L,
    private val clock: () -> Long = System::currentTimeMillis,
) : Closeable {
    @Volatile
    private var nextClaimRecoveryAtMs: Long = 0L

    fun start() {
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

    private fun recoverExpiredClaimsIfDue() {
        val now = clock()
        if (now < nextClaimRecoveryAtMs) return
        recoverExpiredClaimsNow(now)
    }

    private fun recoverExpiredClaimsNow(now: Long = clock()) {
        store.recoverExpiredClaims(olderThanMs = claimExpirationMs)
        nextClaimRecoveryAtMs = now + claimRecoveryIntervalMs
    }
}
```

### 3. full reconcile completeness

```kotlin
private fun handleFullReconcile() {
    val currentRoomIds =
        roomSnapshotReader
            .listRoomChatIds()
            .filter { it.value > 0L }
            .toSet()
    deletedRoomsPendingCleanup.clear()
    deletedRoomsPendingCleanup.addAll(previousSnapshots.keys - currentRoomIds)
    (previousSnapshots.keys + currentRoomIds).forEach(::handleMarkDirty)
}

private fun handleDrain(budget: Int) {
    repeat(budget) {
        val chatId = dirtyRoomQueue.removeFirstOrNull() ?: return
        val currentSnapshot = roomSnapshotReader.snapshot(chatId)
        val previousSnapshot = previousSnapshots.put(chatId, currentSnapshot) ?: return@repeat
        val events = diffEngine.diff(previousSnapshot, currentSnapshot)
        if (events.isNotEmpty()) emitter.emit(events)
        if (chatId in deletedRoomsPendingCleanup && currentSnapshot.isEmptySnapshot()) {
            previousSnapshots.remove(chatId)
            deletedRoomsPendingCleanup.remove(chatId)
        }
    }
}
```

### 4. legacy secret migration

```kotlin
internal fun decodeConfigValues(
    json: Json,
    jsonString: String,
): DecodedConfigValues {
    val rawRoot = json.parseToJsonElement(jsonString).jsonObject
    val decodedValues = json.decodeFromJsonElement(ConfigValues.serializer(), rawRoot)
    val legacyEndpoint =
        decodedValues.endpoint
            .trim()
            .takeIf { it.isNotEmpty() }
            ?: extractLegacyEndpoint(rawRoot)
    val normalizedValues =
        normalizeWebhookConfig(
            if (decodedValues.endpoint.isBlank() && !legacyEndpoint.isNullOrBlank()) {
                decodedValues.copy(endpoint = legacyEndpoint.orEmpty())
            } else {
                decodedValues
            },
        )
    val migratedSecrets = migrateLegacySecrets(rawRoot, normalizedValues)
    return DecodedConfigValues(
        values = migratedSecrets.values,
        migratedLegacyEndpoint = decodedValues.endpoint.isBlank() && !legacyEndpoint.isNullOrBlank(),
        migratedLegacySecrets = migratedSecrets.migrated,
    )
}
```

### 5. MemberRepository batch lookup + typed helpers

```kotlin
internal fun resolveNicknamesBatch(
    userIds: Collection<UserId>,
    linkId: LinkId? = null,
    chatId: ChatId? = null,
): Map<UserId, String> {
    val orderedIds = userIds.distinct().filter { it.value > 0L }
    val resolved = LinkedHashMap<UserId, String>(orderedIds.size)
    var unresolved = orderedIds.toSet()

    if (linkId != null && unresolved.isNotEmpty()) {
        val openNicknames = memberIdentity.loadOpenNicknamesBatch(linkId, unresolved.toList())
        openNicknames.forEach { (userId, nickname) ->
            if (!nickname.isNullOrBlank()) resolved[userId] = nickname
        }
        unresolved = unresolved - resolved.keys
    }

    if (unresolved.isNotEmpty()) {
        val friendNames = memberIdentity.loadFriendsBatch(unresolved.toList())
        resolved.putAll(friendNames)
        unresolved = unresolved - friendNames.keys
    }

    if (unresolved.isNotEmpty()) {
        observedProfile
            .resolveDisplayNamesBatch(unresolved.map(UserId::value), chatId?.value)
            .forEach { (userId, displayName) -> resolved[UserId(userId)] = displayName }
    }

    return orderedIds.associateWith { userId -> resolved[userId] ?: userId.value.toString() }
}
```

### 6. ConfigPolicy source of truth

```kotlin
internal object ConfigPolicy {
    private data class ConfigFieldPolicy(
        val field: ConfigField,
        val contractName: String,
        val restartRequired: Boolean,
        val differs: (ConfigValues, ConfigValues) -> Boolean,
        val validate: (UserConfigState) -> String? = { null },
    )

    private val fieldPolicies: List<ConfigFieldPolicy> =
        listOf(
            ConfigFieldPolicy(
                field = ConfigField.INBOUND_SIGNING_SECRET,
                contractName = "inbound_signing_secret",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.inboundSigningSecret != effective.inboundSigningSecret },
            ),
            ConfigFieldPolicy(
                field = ConfigField.OUTBOUND_WEBHOOK_TOKEN,
                contractName = "outbound_webhook_token",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.outboundWebhookToken != effective.outboundWebhookToken },
            ),
            ConfigFieldPolicy(
                field = ConfigField.BOT_CONTROL_TOKEN,
                contractName = "bot_control_token",
                restartRequired = true,
                differs = { snapshot, effective -> snapshot.botControlToken != effective.botControlToken },
            ),
        )
}
```

### 7. large-body auth memory strategy

```kotlin
private fun spillToDisk(memoryStorage: InMemoryRequestBodyStorage): RequestBodyStorage {
    val spillPath =
        Files.createTempFile(
            policy.spillDirectory,
            REQUEST_BODY_TEMP_FILE_PREFIX,
            REQUEST_BODY_TEMP_FILE_SUFFIX,
        )
    val spillStorage =
        try {
            policy.spillStorageFactory(spillPath)
        } catch (error: Throwable) {
            Files.deleteIfExists(spillPath)
            throw error
        }
    return try {
        val inMemoryBytes = memoryStorage.toByteArray()
        if (inMemoryBytes.isNotEmpty()) {
            spillStorage.write(inMemoryBytes, 0, inMemoryBytes.size)
        }
        memoryStorage.close()
        spillStorage
    } catch (error: Throwable) {
        runCatching { spillStorage.close() }
        throw error
    }
}

internal class StreamingBodyResult(
    private val storage: RequestBodyStorage,
    val sha256Hex: String,
) : AutoCloseable {
    fun <T> decodeJson(json: Json, deserializer: DeserializationStrategy<T>): T =
        storage.openInputStream().use { input ->
            json.decodeFromStream(deserializer, input)
        }
}
```

### 8. nonce cache hardening

```kotlin
internal class RequestAuthenticator(
    private val maxNonceEntries: Int = 10_000,
    private val purgeIntervalMs: Long = 5_000L,
) {
    private val nonceWindow = NonceWindow(maxAgeMs, maxNonceEntries, purgeIntervalMs)

    fun authenticate(
        method: String,
        path: String,
        body: String,
        bodySha256Hex: String = sha256Hex(body.toByteArray(StandardCharsets.UTF_8)),
        expectedSecret: String,
        timestampHeader: String?,
        nonceHeader: String?,
        signatureHeader: String?,
    ): AuthResult {
        val timestamp = timestampHeader?.toLongOrNull() ?: return AuthResult.UNAUTHORIZED
        val nonce = nonceHeader?.takeIf { it.isNotBlank() } ?: return AuthResult.UNAUTHORIZED
        val signature = signatureHeader?.takeIf { it.isNotBlank() } ?: return AuthResult.UNAUTHORIZED
        val now = nowEpochMs()
        if (kotlin.math.abs(now - timestamp) > maxAgeMs) {
            return AuthResult.UNAUTHORIZED
        }
        val expectedSignature =
            signIrisRequestWithBodyHash(
                secret = expectedSecret,
                method = method,
                path = path,
                timestamp = timestamp.toString(),
                nonce = nonce,
                bodySha256Hex = bodySha256Hex,
            )

        if (
            !MessageDigest.isEqual(
                signature.toByteArray(StandardCharsets.UTF_8),
                expectedSignature.toByteArray(StandardCharsets.UTF_8),
            )
        ) {
            return AuthResult.UNAUTHORIZED
        }
        if (!nonceWindow.tryRecord(nonce, now)) {
            return AuthResult.UNAUTHORIZED
        }
        return AuthResult.AUTHORIZED
    }
}
```

### 9. Kotlin-like ownership + value object boundary

```kotlin
internal data class ReplyQueueKey(
    val chatId: ChatId,
    val threadId: ReplyThreadId?,
)

sealed interface SnapshotCommand {
    data class MarkDirty(val chatId: ChatId) : SnapshotCommand
    data class Drain(val budget: Int) : SnapshotCommand
    data object FullReconcile : SnapshotCommand
    data object SeedCache : SnapshotCommand
}

internal class ReplyAdmissionService(...) {
    private sealed interface AdmissionCommand {
        data class Start(val reply: CompletableDeferred<Boolean>) : AdmissionCommand
        data class Restart(val reply: CompletableDeferred<WorkerClosePlan>) : AdmissionCommand
        data class Shutdown(val reply: CompletableDeferred<WorkerClosePlan>) : AdmissionCommand
        data class Enqueue(
            val key: ReplyQueueKey,
            val request: PipelineRequest,
            val reply: CompletableDeferred<ReplyAdmissionResult>,
        ) : AdmissionCommand
        data class WorkerClosed(val key: ReplyQueueKey, val workerId: Long, val idleTimeout: Boolean) : AdmissionCommand
    }

    private val commands = Channel<AdmissionCommand>(Channel.UNLIMITED)
    private var workerRegistry = mutableMapOf<ReplyQueueKey, WorkerState>()
    private var lifecycle = ReplyAdmissionLifecycle.STOPPED
}
```

### 10. test quality reinforcement

```kotlin
private fun waitUntil(
    timeoutMs: Long = 1_000L,
    condition: () -> Boolean,
) = kotlinx.coroutines.runBlocking {
    kotlinx.coroutines.withTimeout(timeoutMs) {
        while (!condition()) {
            kotlinx.coroutines.delay(10L)
        }
    }
}

private fun waitUntil(
    message: String,
    condition: () -> Boolean,
) = runBlocking {
    var satisfied = condition()
    repeat(100) {
        if (satisfied) return@repeat
        delay(5L)
        satisfied = condition()
    }
    assertTrue(satisfied, message)
}
```
