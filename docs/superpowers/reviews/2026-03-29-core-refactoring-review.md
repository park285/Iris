# Iris Core Refactoring — External Review Package

**Branch:** `refactor/core-type-safety-and-hot-path`
**Base:** `d6db586` (main)
**Head:** `d575b29`
**Stats:** 31 files changed, +2091 -960

---

## Commits (oldest first)

| # | SHA | Message |
|---|-----|---------|
| 1 | `ddf56fc` | refactor: replace reply status string literals with ReplyLifecycleState enum |
| 2 | `6855b60` | fix: register nonce after signature verification to prevent nonce exhaustion |
| 3 | `6ff4174` | refactor: make GlobalDispatchGate schedule start times only |
| 4 | `a891ce7` | refactor: separate snapshot diff from 100ms polling hot path into SnapshotObserver |
| 5 | `3eb89cf` | fix: use chat_id index instead of LIKE scan in resolveObservedProfileByChatId |
| 6 | `70a75d5` | perf: batch nickname resolution in MemberRepository to eliminate N+1 queries |
| 7 | `4527657` | refactor: consolidate ConfigManager state into immutable ConfigRuntimeState bundle |
| 8 | `7ff2d50` | refactor: accept typed query results in MemberRepository |
| 9 | `b68c851` | style: apply ktlint format to all changed files |
| 10 | `e2ef338` | test: add unknown enum state deserialization failure test |
| 11 | `d575b29` | feat: externalize routing config defaults and defer image decode to worker |

---

## 1. ReplyLifecycleState enum (`ddf56fc`)

**Problem:** Reply status tracked as raw strings (`"queued"`, `"sending"`, etc.) — typos invisible at compile time, no exhaustive matching.

**Change:** Introduce `@Serializable enum class ReplyLifecycleState` with `@SerialName` for wire format preservation. Migrate `ReplyStatusSnapshot.state`, `ReplyStatusStore.update()`, and all 6 call sites in `ReplyService`.

### New file: `model/ReplyLifecycleState.kt`

```kotlin
@Serializable
enum class ReplyLifecycleState {
    @SerialName("queued") QUEUED,
    @SerialName("preparing") PREPARING,
    @SerialName("prepared") PREPARED,
    @SerialName("sending") SENDING,
    @SerialName("handoff_completed") HANDOFF_COMPLETED,
    @SerialName("failed") FAILED,
}
```

### Changed: `model/ReplyStatusSnapshot.kt`

```diff
 data class ReplyStatusSnapshot(
     val requestId: String,
-    val state: String,
+    val state: ReplyLifecycleState,
     val updatedAtEpochMs: Long,
     val detail: String? = null,
 )
```

### Changed: `ReplyStatusStore.kt`

```diff
 fun update(
     requestId: String,
-    state: String,
+    state: ReplyLifecycleState,
     detail: String? = null,
 )
```

### Changed: `ReplyService.kt` (6 call sites)

```diff
-request.requestId?.let { statusStore.update(it, "preparing") }
+request.requestId?.let { statusStore.update(it, ReplyLifecycleState.PREPARING) }
 // ... same pattern for PREPARED, SENDING, HANDOFF_COMPLETED, FAILED, QUEUED
```

### Tests: `ReplyStatusStoreTest.kt`

- `lifecycle state serializes to lowercase json string` — verifies `"queued"` in JSON output
- `all lifecycle states round-trip through json` — every enum value encodes/decodes
- `store update and get with typed state` — typed API works
- `unknown state string fails deserialization` — `"unknown"` throws `SerializationException`

---

## 2. RequestAuthenticator nonce ordering (`6855b60`)

**Problem:** Nonce registered *before* signature verification. Invalid signatures consume valid nonces, enabling nonce exhaustion attacks.

**Change:** Move `purgeExpiredNonces()` + `putIfAbsent()` after `MessageDigest.isEqual()` check.

### Changed: `RequestAuthenticator.kt`

```diff
 // BEFORE: nonce registered, then signature checked
-purgeExpiredNonces(now)
-if (nonceTimestamps.putIfAbsent(nonce, now) != null) {
-    return AuthResult.UNAUTHORIZED
-}
 val expectedSignature = signIrisRequestWithBodyHash(...)
-return if (MessageDigest.isEqual(...)) AuthResult.AUTHORIZED
-       else AuthResult.UNAUTHORIZED

 // AFTER: signature checked first, nonce registered only on valid sig
+val expectedSignature = signIrisRequestWithBodyHash(...)
+if (!MessageDigest.isEqual(
+        signature.toByteArray(StandardCharsets.UTF_8),
+        expectedSignature.toByteArray(StandardCharsets.UTF_8),
+    )
+) {
+    return AuthResult.UNAUTHORIZED
+}
+purgeExpiredNonces(now)
+if (nonceTimestamps.putIfAbsent(nonce, now) != null) {
+    return AuthResult.UNAUTHORIZED
+}
+return AuthResult.AUTHORIZED
```

### Test: `RequestAuthenticatorTest.kt`

```kotlin
@Test
fun `invalid signature does not consume nonce`() {
    // bad sig first → UNAUTHORIZED, nonce NOT consumed
    // same nonce with valid sig → AUTHORIZED (nonce available)
}
```

---

## 3. GlobalDispatchGate schedule-only (`6ff4174`)

**Problem:** `dispatch(block)` held a Mutex during the entire send execution. Slow sends (image uploads) blocked ALL other workers globally.

**Change:** Replace `dispatch(block)` with `awaitPermit()` — mutex only held to calculate next slot, released before actual send.

### Changed: `GlobalDispatchGate.kt` (full rewrite)

```kotlin
// BEFORE: mutex wraps block execution
suspend fun <T> dispatch(block: suspend () -> T): T =
    mutex.withLock {
        delay(waitMs)
        val result = block()  // <-- lock held during send
        nextAllowedAtMs = clock() + baseMs + jitterMs
        result
    }

// AFTER: mutex only for scheduling
suspend fun awaitPermit() {
    val waitMs = mutex.withLock {
        val scheduledAt = maxOf(now, nextAllowedAtMs)
        nextAllowedAtMs = scheduledAt + baseMs + jitterMs  // schedule next slot
        (scheduledAt - now).coerceAtLeast(0L)
    }
    if (waitMs > 0) delay(waitMs)  // wait outside lock
}
```

### Changed: `ReplyService.kt`

```diff
-dispatchGate.dispatch {
-    request.requestId?.let { statusStore.update(it, ReplyLifecycleState.SENDING) }
-    request.send()
-}
+dispatchGate.awaitPermit()
+request.requestId?.let { statusStore.update(it, ReplyLifecycleState.SENDING) }
+request.send()
```

**Semantic change:** Sends can now overlap in execution time. Only start times are paced. Tests updated to verify pacing, not mutual exclusion.

---

## 4. ObserverHelper hot path separation (`a891ce7`)

**Problem:** Every 100ms polling tick called `runSnapshotDiff()` which scanned ALL rooms — even with no new messages. This made the effective poll interval 100ms + snapshot_scan_time.

**Change:**
- `checkChange()` now only polls new logs and marks rooms dirty via `markRoomDirty(chatId)`
- New `SnapshotObserver` runs dirty-room diffs on a 5-second interval
- Full room scan removed from hot path

### Changed: `ObserverHelper.kt`

```diff
+private val dirtyRoomSet = ConcurrentHashMap.newKeySet<Long>()
+private val dirtyRoomQueue = ConcurrentLinkedQueue<Long>()

 fun checkChange() {
     // ... poll new logs ...
     for (logEntry in newLogs) {
+        markRoomDirty(logEntry.chatId)
         if (!processLogEntry(logEntry)) break
     }
-    if (snapshotManager != null && memberRepo != null && sseEventBus != null) {
-        runSnapshotDiff()  // REMOVED from hot path
-    }
 }

+fun runDirtySnapshotDiff(maxRoomsPerTick: Int = 32) {
+    repeat(maxRoomsPerTick) {
+        val chatId = dirtyRoomQueue.poll() ?: return
+        dirtyRoomSet.remove(chatId)
+        val current = repo.snapshot(chatId)
+        val prev = previousSnapshots.put(chatId, current) ?: return@repeat
+        emitSnapshotEvents(snapMgr.diff(prev, current), bus)
+    }
+}
```

### New file: `SnapshotObserver.kt`

```kotlin
internal class SnapshotObserver(
    private val observerHelper: ObserverHelper,
    private val intervalMs: Long = 5_000L,
    private val maxRoomsPerTick: Int = 32,
) {
    fun start() {
        job = coroutineScope.launch {
            while (isActive) {
                observerHelper.runDirtySnapshotDiff(maxRoomsPerTick)
                delay(intervalMs)
            }
        }
    }
}
```

### Changed: `AppRuntime.kt`

```diff
 dbObserver.startPolling()
+snapshotObserver = SnapshotObserver(observerHelper)
+snapshotObserver.start()

 // stop():
+snapshotObserver.stop()
```

### Tests: `ObserverHelperSnapshotTest.kt`

- `checkChange does not invoke snapshot diff on every tick` — snapshotManager.diff count = 0 after 10 ticks
- `runDirtySnapshotDiff processes only rooms marked dirty` — dirty chatId=2 → only room 2 diffed
- Additional tests for seed, dirty marking dedup, etc.

---

## 5. MemberRepository chat_id index fix (`3eb89cf`)

**Problem:** `resolveObservedProfileByChatId` used `WHERE notification_key LIKE '%|42|%'` — full table scan ignoring the existing `observed_profiles(chat_id, updated_at DESC)` index.

**Change:**

```diff
-WHERE notification_key LIKE ?    -- bind: "%|$chatId|%"
+WHERE chat_id = ?                -- bind: chatId.toString()
```

### Test: `MemberRepositoryQueryTest.kt`

```kotlin
@Test
fun `observed profile query uses chat_id equality not LIKE`() {
    // captures SQL, verifies no LIKE in observed_profiles query
}
```

---

## 6. Batch nickname resolution (`70a75d5`)

**Problem:** `snapshot()` and `resolveNonOpenRoomName()` called `resolveNickname()` per member — N queries for N members (N+1 pattern).

**Change:** New `resolveNicknamesBatch()` uses `IN (...)` clauses with 3-tier fallback: open_chat_member → friends → observed_profile_user_links.

### New method: `MemberRepository.resolveNicknamesBatch()`

```kotlin
internal fun resolveNicknamesBatch(
    userIds: Collection<Long>,
    linkId: Long? = null,
    chatId: Long? = null,
): Map<Long, String> {
    // 1. open_chat_member WHERE link_id=? AND user_id IN (...)
    // 2. friends WHERE id IN (...) — only unresolved
    // 3. observed_profile_user_links — only remaining unresolved
    // 4. fallback: userId.toString()
}
```

### Callers updated:

```diff
 // snapshot() — was per-user loop
-for (memberId in memberIds) {
-    val nick = resolveNickname(memberId, ...)
-    ...
-}
+resolveNicknamesBatch(memberIds, linkId, chatId).forEach { (uid, nick) ->
+    if (nick.isNotBlank() && nick != uid.toString()) nicknames[uid] = nick
+}

 // resolveNonOpenRoomName() — same pattern
-memberIds.mapNotNull { resolveNickname(it, chatId = chatId) }
+resolveNicknamesBatch(memberIds.toList(), chatId = chatId).values.filter { it.isNotBlank() }
```

### Tests:

- `batch nickname resolution reduces query count` — 5 users resolved in ≤2 queries
- `batch nickname falls back through open, friends, observed` — fallback chain verified

---

## 7. ConfigManager state bundle (`4527657`)

**Problem:** 4 separate `@Volatile` fields (`snapshotUser`, `appliedUser`, `discoveredState`, `isDirty`) — scattered state makes concurrency reasoning difficult. Each setter had duplicate synchronized blocks.

**Change:** Single `ConfigRuntimeState` data class + `mutateState()`/`updateUserState()` helpers.

### New internal type:

```kotlin
private data class ConfigRuntimeState(
    val snapshotUser: UserConfigState = UserConfigState(),
    val appliedUser: UserConfigState = UserConfigState(),
    val discovered: DiscoveredConfigState = DiscoveredConfigState(),
    val isDirty: Boolean = false,
)

@Volatile
private var state = ConfigRuntimeState()
```

### Setter pattern (before/after):

```diff
 // BEFORE — each setter manually:
 override var messageSendRate: Long
     get() = appliedUser.messageSendRate
     set(value) {
         synchronized(this) {
             if (snapshotUser.messageSendRate == value && appliedUser.messageSendRate == value) return
             snapshotUser = snapshotUser.copy(messageSendRate = value)
             appliedUser = appliedUser.copy(messageSendRate = value)
             markDirty()
         }
     }

 // AFTER — single helper:
 override var messageSendRate: Long
     get() = state.appliedUser.messageSendRate
     set(value) {
         updateUserState(applyImmediately = true) { it.copy(messageSendRate = value) }
     }

 // Restart-required fields use applyImmediately = false:
 override var botSocketPort: Int
     get() = state.appliedUser.botHttpPort
     set(value) {
         updateUserState(applyImmediately = false) { it.copy(botHttpPort = value) }
     }
```

### Tests: `ConfigManagerStateTest.kt`

- `immediate apply field changes getter immediately` — messageSendRate round-trips through save/reload
- `restart-required field updates snapshot but not applied` — botSocketPort getter unchanged, but persisted value updates
- `botId change does not mark config dirty` — discovered state not persisted

---

## 8. MemberRepository QueryRow typed wrapper (`7ff2d50`)

**Problem:** `AppRuntime` converted `QueryExecutionResult` → `List<Map<String, String?>>` via `toLegacyQueryRows()` before passing to `MemberRepository`. Type information discarded at the boundary.

**Change:** MemberRepository now accepts `QueryExecutionResult` directly. Internal `QueryRow` wrapper provides typed accessors. Internal legacy bridge preserves all existing query code.

### Constructor change:

```diff
 class MemberRepository(
-    private val executeQuery: (String, Array<String?>?, Int) -> List<Map<String, String?>>,
+    private val executeQueryTyped: (String, Array<String?>?, Int) -> ChatLogRepository.QueryExecutionResult,
     ...
-)
+) {
+    // Internal bridge — existing query code unchanged
+    private fun executeQuery(sql: String, args: Array<String?>?, maxRows: Int): List<Map<String, String?>> {
+        val result = executeQueryTyped(sql, args, maxRows)
+        return queryRows(result).map { it.toMap(columns) }
+    }
```

### Internal QueryRow:

```kotlin
private class QueryRow(
    private val columnIndex: Map<String, Int>,
    private val values: List<JsonElement?>,
) {
    fun string(name: String): String?
    fun long(name: String): Long?
    fun int(name: String): Int?
    fun toMap(columns: List<String>): Map<String, String?>
}
```

### AppRuntime:

```diff
 memberRepo = MemberRepository(
-    executeQuery = { sqlQuery, bindArgs, maxRows ->
-        toLegacyQueryRows(kakaoDb.executeQuery(sqlQuery, bindArgs, maxRows))
-    },
+    executeQueryTyped = kakaoDb::executeQuery,
     ...
 )
```

---

## Bonus: Routing config externalization (`d575b29`)

Separate feature stashed before refactoring, merged after:

- **Delete `RoutingConfigDefaults.kt`** — hardcoded routing defaults removed
- **ConfigSerialization** — routing map fields default to `emptyMap()` instead of seeded values
- **ReplyService** — defer base64 image decode from admission to worker prepare phase
- **iris-daemon** — switch start command from CLI args to env-based invocation
- **iris-common** — expand bridge diagnostics response model to match runtime payload

---

## Quality Gate

```
ktlintCheck  ✅
lint         ✅
assembleDebug ✅
testDebugUnitTest ✅ (all modules)
```

## Review Focus Areas

1. **Security:** Nonce ordering fix (#2) — verify the reordering doesn't create a TOCTOU gap
2. **Concurrency:** GlobalDispatchGate (#3) — sends now overlap; is pacing-only sufficient for KakaoTalk rate limits?
3. **Performance:** Snapshot separation (#4) — 5s interval + dirty-room-only; is eventual consistency acceptable for member events?
4. **Correctness:** Batch nickname resolution (#6) — verify IN(...) clause behavior with empty sets and fallback ordering
5. **State management:** ConfigManager bundle (#7) — verify restart-gated fields (botSocketPort) don't leak through updateUserState
