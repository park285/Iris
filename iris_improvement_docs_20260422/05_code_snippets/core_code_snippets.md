# 코드 스니펫 모음

핵심 변경안만 빠르게 찾기 위한 모음입니다. 실제 적용 시 각 STEP 문서의 테스트/리스크도 함께 확인하세요.


## STEP 01. closeout 테스트 번들 무결성 복구

```bash
required_scripts=(
  "scripts/replay_closeout.sh"
  "scripts/verify_closeout_packet.py"
  "scripts/closeout_facts.py"
)

for file in "${required_scripts[@]}"; do
  if [[ ! -f "$repo_root/$file" ]]; then
    echo "missing required closeout script: $file" >&2
    exit 1
  fi
done
```

복구 후 기대 파일 구조입니다.

```text
scripts/
  replay_closeout.sh
  verify_closeout_packet.py
  closeout_facts.py
  zygisk_next_bootstrap.sh
  zygisk_next_watchdog.sh
  check-bridge-boundaries.sh
```


## STEP 02. bridge optional/readiness 계약 정리

```kotlin
internal data class RuntimeConfigReadiness(
    val inboundSigningSecretConfigured: Boolean,
    val outboundWebhookTokenConfigured: Boolean,
    val botControlTokenConfigured: Boolean,
    val bridgeTokenConfigured: Boolean,
    val defaultWebhookEndpointConfigured: Boolean,
    val bridgeRequired: Boolean,
) {
    fun bootstrapState(): RuntimeBootstrapState =
        when {
            !inboundSigningSecretConfigured ->
                RuntimeBootstrapState.Blocked("inbound signing secret not configured")

            !outboundWebhookTokenConfigured ->
                RuntimeBootstrapState.Blocked("outbound webhook token not configured")

            !botControlTokenConfigured ->
                RuntimeBootstrapState.Blocked("bot control token not configured")

            bridgeRequired && !bridgeTokenConfigured ->
                RuntimeBootstrapState.Blocked("bridge token not configured")

            !defaultWebhookEndpointConfigured ->
                RuntimeBootstrapState.Blocked("webhook endpoint not configured")

            else -> RuntimeBootstrapState.Ready
        }
}
```

```kotlin
internal fun readinessFailureReason(
    bridgeHealth: ImageBridgeHealthResult?,
    configReadiness: RuntimeConfigReadiness? = null,
): String? {
    when (val bootstrapState = configReadiness?.bootstrapState()) {
        null, RuntimeBootstrapState.Ready -> Unit
        is RuntimeBootstrapState.Blocked ->
            return "config not ready: ${bootstrapState.reason}"
    }

    val bridgeRequired = configReadiness?.bridgeRequired == true
    if (bridgeRequired && bridgeHealth != null && !isBridgeReady(bridgeHealth)) {
        return "bridge not ready"
    }

    return null
}
```


## STEP 03. 설정 변경 API 원자성 보장

```kotlin
internal data class ConfigMutationPlan(
    val responseName: String,
    val candidateSnapshot: UserConfigState,
    val applyImmediately: Boolean,
    val requiresRestart: Boolean,
)
```

```kotlin
internal object ConfigPolicy {
    fun planUpdate(
        current: UserConfigState,
        name: String,
        request: ConfigRequest,
    ): ConfigMutationPlan {
        return when (name) {
            "dbrate" -> planDbRateUpdate(current, name, request)
            "sendrate" -> planSendRateUpdate(current, name, request)
            "botport" -> planBotPortUpdate(current, name, request)
            "endpoint" -> planEndpointUpdate(current, name, request)
            else -> throw ApiRequestException("unknown config name: $name")
        }
    }
}
```

```kotlin
internal fun persistThenCommit(plan: ConfigMutationPlan): Boolean {
    val current = stateStore.current()
    val candidateRuntime =
        current.copy(
            snapshotUser = plan.candidateSnapshot,
            appliedUser =
                if (plan.applyImmediately) {
                    plan.candidateSnapshot
                } else {
                    current.appliedUser
                },
            isDirty = true,
        )

    if (!persistence.save(candidateRuntime.snapshotUser)) {
        return false
    }

    stateStore.replace(candidateRuntime.copy(isDirty = false))
    return true
}
```


## STEP 04. config 파일 로드 validation 강화

```kotlin
fun validate(state: UserConfigState): List<ConfigValidationError> =
    buildList {
        fieldPolicies.mapNotNullTo(this) { policy ->
            policy.validate(state)?.let { ConfigValidationError(policy.field, it) }
        }

        validateWebhookEndpoint(state.endpoint.trim())?.let { message ->
            add(ConfigValidationError(ConfigField.ROUTING_POLICY, "endpoint: $message"))
        }

        state.webhooks.forEach { (route, endpoint) ->
            val normalizedRoute = canonicalWebhookRoute(route)
            validateWebhookRoute(normalizedRoute)?.let { message ->
                add(ConfigValidationError(ConfigField.ROUTING_POLICY, "webhooks.$route: $message"))
            }

            validateWebhookEndpoint(endpoint.trim())?.let { message ->
                add(ConfigValidationError(ConfigField.ROUTING_POLICY, "webhooks.$route: $message"))
            }
        }

        validateSecret("inboundSigningSecret", state.inboundSigningSecret)?.let {
            add(ConfigValidationError(ConfigField.INBOUND_SIGNING_SECRET, it))
        }
    }
```

```kotlin
private fun validateSecret(name: String, value: String): String? {
    if (value.isEmpty()) return null
    if (value != value.trim()) return "$name must not have leading or trailing whitespace"
    if (value.any { it.isISOControl() }) return "$name must not contain control characters"
    return null
}
```


## STEP 05. shell quoting 및 명령 주입 방어

```bash
sh_quote() {
  printf "'%s'" "$(printf '%s' "$1" | sed "s/'/'\\\\''/g")"
}

env_assign() {
  local name="$1"
  local value="$2"
  printf '%s=%s ' "$name" "$(sh_quote "$value")"
}
```

```bash
build_remote_runtime_command() {
  local redirect_suffix="${1:-}"

  {
    env_assign IRIS_CONFIG_PATH "$IRIS_CONFIG_PATH"
    env_assign IRIS_WEBHOOK_TOKEN "$IRIS_WEBHOOK_TOKEN"
    env_assign IRIS_BOT_TOKEN "$IRIS_BOT_TOKEN"
    env_assign IRIS_LOG_LEVEL "$IRIS_LOG_LEVEL"
    env_assign CLASSPATH "$IRIS_APK_PATH"
    printf 'exec app_process / party.qwer.iris.Main%s' "$redirect_suffix"
  }
}

remote_command="$(build_remote_runtime_command ' > /dev/null 2>&1 &')"
"${ADB_CMD[@]}" shell "su root sh -c $(sh_quote "$remote_command")"
```

```rust
let config_path = shell_quote(&cfg.init.config_dest);
let device_config = match adb.shell(&format!("cat {config_path}")).await {
    Ok(content) => content,
    Err(error) => {
        tracing::warn!(error = %error, "디바이스 config 읽기 실패 — config drift check 건너뜀");
        return Ok(false);
    }
};
```


## STEP 06. SSE 재연결 이벤트 유실 race 수정

```kotlin
data class OpenSubscriberWithReplay(
    val afterId: Long,
    val reply: CompletableDeferred<SubscriberReplay>,
) : SseCommand

internal data class SubscriberReplay(
    val replay: List<SseEventEnvelope>,
    val channel: Channel<SseEventEnvelope>,
)
```

```kotlin
val opened = bus.openSubscriberWithReplaySuspend(lastEventId)
val channel = opened.channel
var lastWrittenEventId = lastEventId

opened.replay.forEach { envelope ->
    if (envelope.id > lastWrittenEventId) {
        writeStringUtf8(formatSseFrame(envelope))
        lastWrittenEventId = envelope.id
    }
}
flush()
```


## STEP 07. SSE frame multiline 안정화

```kotlin
internal fun formatSseFrame(event: SseEventEnvelope): String =
    buildString {
        append("id: ").append(event.id).append('\n')
        append("event: ").append(sanitizeSseField(event.eventType)).append('\n')

        event.payload
            .replace("\r\n", "\n")
            .replace('\r', '\n')
            .split('\n')
            .forEach { line ->
                append("data: ").append(line).append('\n')
            }

        append('\n')
    }
```

```rust
let mut data_lines: Vec<&str> = Vec::new();

if let Some(data) = line.strip_prefix("data: ") {
    data_lines.push(data);
}

let data = data_lines.join("\n");
serde_json::from_str::<SseEvent>(&data).ok()
```


## STEP 08. SseEventBus actor 예외 안정성 강화

```kotlin
for (command in commands) {
    try {
        handleCommand(command)
    } catch (error: Throwable) {
        IrisLogger.error("[SseEventBus] actor command failed: ${error.message}", error)
        failCommand(command, error)
    }
}
```

```kotlin
private fun failCommand(command: SseCommand, error: Throwable) {
    when (command) {
        is SseCommand.Emit -> command.reply.completeExceptionally(error)
        is SseCommand.Replay -> command.reply.complete(emptyList())
        is SseCommand.SubscriberCount -> command.reply.complete(0)
        is SseCommand.Close -> command.reply.complete(Unit)
        else -> Unit
    }
}
```


## STEP 09. Webhook outbox dispatcher 생명주기 동시성 수정

```kotlin
private val lifecycleLock = Any()

fun start() {
    synchronized(lifecycleLock) {
        if (pollingJob?.isActive == true) return
        shuttingDown = false
        recoverExpiredClaimsNow()
        pollingJob = coroutineScope.launch { pollingLoop() }
    }
}
```


## STEP 10. Webhook outbox claim 안정화

```kotlin
val updated = update(/* UPDATE ... WHERE id = ? AND status IN (...) */, listOf(...))
if (updated != 1) {
    IrisLogger.warn("[WebhookOutbox] stale claim candidate ignored: id=${candidate.id}")
    return@mapNotNull null
}
```


## STEP 11. SQLite statement close 누락 수정

```kotlin
database.compileStatement(sql).use { stmt ->
    // bind args
    return stmt.executeUpdateDelete()
}
```


## STEP 12. 종료 순서와 checkpoint flush 수정

```kotlin
override fun close() {
    ingressService.close()
    checkpointJournal.flushNow()
}
```


## STEP 13. ReplyAdmissionService actor 소유 상태 경계 수정

```kotlin
data class ShutdownCompleted(
    val reply: CompletableDeferred<Unit>,
) : AdmissionCommand

private fun handleShutdownCompleted(command: AdmissionCommand.ShutdownCompleted) {
    closingWorkers.clear()
    command.reply.complete(Unit)
}
```


## STEP 14. multipart 입력 처리 강화

```kotlin
when (part) {
    is PartData.FormItem -> {
        if (part.name != "metadata") invalidRequest("unsupported multipart form part: ${part.name}")
        acceptMetadata(part, multipart)
    }
    is PartData.FileItem -> {
        if (part.name != "image") invalidRequest("unsupported multipart file part: ${part.name}")
        acceptImage(part)
    }
    else -> invalidRequest("unsupported multipart part")
}
```


## STEP 15. 요청 body size 계산 overflow 방어

```kotlin
if (partBytes < 0) requestRejected("invalid part size", HttpStatusCode.BadRequest)
if (current > maxBytes || partBytes > maxBytes - current) {
    requestRejected("request body too large", HttpStatusCode.PayloadTooLarge)
}
return current + partBytes
```


## STEP 16. 인증 nonce replay/DoS 경계 강화

```kotlin
data class SignaturePrecheck(...) {
    val nonceKey: String
        get() = "$method\n$path\n$timestampEpochMs\n$nonce"
}
```


## STEP 17. webhook endpoint default/route 정책 정리

```kotlin
private fun bootstrapFailureReason(): String? {
    val inboundConfigured = config.activeInboundSigningSecret().isNotBlank()
    val outboundConfigured = config.activeOutboundWebhookToken().isNotBlank()
    val botControlConfigured = config.activeBotControlToken().isNotBlank()

    return when {
        !inboundConfigured -> "inbound signing secret not configured"
        !outboundConfigured -> "outbound webhook token not configured"
        !botControlConfigured -> "bot control token not configured"
        else -> null
    }
}
```


## STEP 18. Rust/Kotlin canonical query 계약 일치화

```rust
fn encode_query_component(value: &str) -> String {
    utf8_percent_encode(value, NON_ALPHANUMERIC).to_string()
}
```


## STEP 19. APK checksum 검증 SHA-256 전환

```bash
if ! [[ "$downloaded_sha256" =~ ^[0-9a-fA-F]{64}$ ]]; then
  echo "Invalid SHA256 checksum format."
  return 1
fi
```


## STEP 20. readiness error 정보 노출 최소화

```kotlin
return if (verbose) rawReason else "not ready"
```


## STEP 21. ConfigStateStore lock 구조 단순화

```kotlin
fun updateUserState(...): ConfigRuntimeState =
    mutate { current ->
        // compute next state only
    }
```


## STEP 22. SqliteSseEventStore prune 입력 검증

```kotlin
override fun prune(keepCount: Int) {
    require(keepCount > 0) { "keepCount must be positive" }
    // DELETE ...
}
```
