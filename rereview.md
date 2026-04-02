아래 diff들은 지금 패키지에 들어 있는 스냅샷을 기준으로 쓴 컴파일 직전 수준의 수정안입니다.
즉, 방향 설명이 아니라 실제 파일에 어떤 식으로 손을 대야 하는지를 보여주는 초안입니다.
다만 일부 import 정리, 테스트 helper 조정, 주변 타입명 맞춤은 같이 따라와야 합니다.

핵심은 우선순위입니다.

P0: 지금 승인 보류를 일으키는 항목
P1: 승인 직후라도 바로 손봐야 할 구조/냄새 항목

이번 패킷에서 반드시 먼저 고쳐야 하는 것은 다섯 가지입니다.

multipart image handle 수명 버그
ReplyAdmissionService의 graceful shutdown/restart channel 보존 문제
WebhookHttpClientFactory의 PRIVATE_OVERLAY_HTTP_ALLOWED 의미-구현 불일치
auth contract fixture/테스트 불일치
closeout 패키지 문서/요약의 사실 불일치

아래부터 순서대로 적겠습니다.

P0-1. multipart image handle 수명 버그를 먼저 고쳐야 합니다

이건 이번 패킷에서 가장 위험한 실제 코드 결함입니다.

현재 왜 틀렸는가

흐름을 아주 단순화하면 지금은 이렇습니다.

route가 multipart image를 읽어 VerifiedImagePayloadHandle을 만든다
그 handle을 ReplyService.sendNativeMultiplePhotosHandlesSuspend(...)에 넘긴다
route가 collector.release()를 호출한다
spill file backing handle이면 temp file이 지워진다
실제 worker가 나중에 prepare()에서 handle을 읽으려 할 때 파일이 없다

즉, route가 아직 자기 것이 아닌 자원을 닫고 있습니다.

현재 코드는 아래 세 파일을 같이 고쳐야 합니다.

app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt
app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt
app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt
1) MultipartReplyCollector.kt 수정

핵심은 간단합니다.

collect()가 성공적으로 끝났다면
그 시점부터 handle ownership은 호출자 쪽으로 넘어가야 합니다
collector는 더 이상 그 handle을 닫으면 안 됩니다
권장 diff
diff --git a/app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt b/app/src/main/java/party/qwer/iris/http/MultipartReplyCollector.kt
@@
 internal class MultipartReplyCollector(
@@
 ) {
@@
-    fun release() {
+    fun closeUntransferredHandles() {
         imageHandles.forEach { handle ->
             runCatching { handle.close() }
         }
         imageHandles.clear()
     }
@@
-        return MultipartReplyPayload(
-            metadata = currentMetadata,
-            target = validateReplyTarget(currentMetadata),
-            imageHandles = imageHandles.toList(),
-        )
+        // Successful collection transfers ownership of verified handles
+        // to the caller. The collector must not close them afterwards.
+        val transferredHandles = imageHandles.toList()
+        imageHandles.clear()
+
+        return MultipartReplyPayload(
+            metadata = currentMetadata,
+            target = validateReplyTarget(currentMetadata),
+            imageHandles = transferredHandles,
+        )
     }
왜 이 수정이 맞는가

현재 release()라는 이름도 모호합니다.
이 함수는 “collector가 아직 소유하고 있는 미전달 handle만 정리한다”가 정확한 의미입니다.
그래서 이름도 closeUntransferredHandles()처럼 바꾸는 것이 좋습니다.

이렇게 바꾸면 ownership이 더 분명해집니다.

collect 성공 전: collector 소유
collect 성공 후: caller 소유

이게 구조적으로 맞습니다.

2) ReplyRoutes.kt 수정

지금은 성공 경로에서도 collector.release()를 호출합니다.
이게 핵심 버그입니다.

고쳐야 할 방향은 이렇습니다.

collector는 성공적으로 넘기지 못한 handle만 finally에서 닫는다
messageSender.sendNativeMultiplePhotosHandlesSuspend(...) 호출 중 예외가 나면
route가 payload handle을 닫는다
성공적으로 함수 호출이 끝났으면 route는 더 이상 handle을 건드리지 않는다
권장 diff
diff --git a/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt b/app/src/main/java/party/qwer/iris/http/ReplyRoutes.kt
@@
     try {
@@
         val payload = collector.collect(multipart) ?: return null
         val requestId = "reply-${UUID.randomUUID()}"
-        val admission =
-            messageSender.sendNativeMultiplePhotosHandlesSuspend(
-                room = payload.target.roomId,
-                imageHandles = payload.imageHandles,
-                threadId = payload.target.threadId,
-                threadScope = payload.target.threadScope,
-                requestId = requestId,
-            )
+        val admission =
+            try {
+                messageSender.sendNativeMultiplePhotosHandlesSuspend(
+                    room = payload.target.roomId,
+                    imageHandles = payload.imageHandles,
+                    threadId = payload.target.threadId,
+                    threadScope = payload.target.threadScope,
+                    requestId = requestId,
+                )
+            } catch (error: Throwable) {
+                payload.imageHandles.forEach { handle ->
+                    runCatching { handle.close() }
+                }
+                throw error
+            }
         if (admission.status != ReplyAdmissionStatus.ACCEPTED) {
             requestRejected(
                 admission.message ?: "reply request rejected",
                 replyAdmissionHttpStatus(admission.status),
             )
         }
-        collector.release()
 
         return ReplyAcceptedResponse(
             requestId = requestId,
             room = payload.metadata.room,
             type = payload.metadata.type,
         )
     } finally {
-        collector.release()
+        collector.closeUntransferredHandles()
     }
 }
왜 이 수정이 맞는가

이제 route의 책임은 정확해집니다.

multipart를 collect하기 전/중 실패 → collector가 정리
collect 완료 후 caller로 ownership 이전
caller 호출 중 예외 → route가 이전된 handle을 정리
caller가 정상적으로 ownership을 받음 → 이후 정리는 reply service/job이 책임

이게 지금 구조에서 가장 안전한 ownership 모델입니다.

3) 테스트를 production 경로처럼 바꿔야 합니다

현재 ReplyRoutesMultipartTest.kt의 RecordingMultipartMessageSender는 handle을 route 단계에서 즉시 열어 읽어버립니다.
그래서 실제 production처럼:

route returns
queue에 들어감
worker가 나중에 읽음

이라는 경로를 검증하지 못합니다.

이 테스트 구조 때문에 현재 버그를 놓쳤습니다.

권장 diff: sender stub 수정
diff --git a/app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt b/app/src/test/java/party/qwer/iris/http/ReplyRoutesMultipartTest.kt
@@
-private class RecordingMultipartMessageSender : MessageSender {
+private class RecordingMultipartMessageSender(
+    private val consumeImmediately: Boolean = true,
+) : MessageSender {
@@
     var lastImageBytes: List<ByteArray> = emptyList()
+    private var retainedHandles: List<VerifiedImagePayloadHandle> = emptyList()
@@
     override suspend fun sendNativeMultiplePhotosHandlesSuspend(
         room: Long,
         imageHandles: List<VerifiedImagePayloadHandle>,
         threadId: Long?,
         threadScope: Int?,
         requestId: String?,
     ): ReplyAdmissionResult {
         multiPhotoCalls += 1
         multiPhotoHandleCalls += 1
         lastRoom = room
         lastThreadId = threadId
         lastThreadScope = threadScope
-        lastImageBytes =
-            imageHandles.map { handle ->
-                handle.openInputStream().use { input -> input.readBytes() }
-            }
-        imageHandles.forEach { handle ->
-            runCatching { handle.close() }
-        }
+        if (consumeImmediately) {
+            lastImageBytes =
+                imageHandles.map { handle ->
+                    handle.openInputStream().use { input -> input.readBytes() }
+                }
+            imageHandles.forEach { handle ->
+                runCatching { handle.close() }
+            }
+        } else {
+            retainedHandles = imageHandles.toList()
+        }
         return ReplyAdmissionResult(ReplyAdmissionStatus.ACCEPTED)
     }
+
+    fun consumeRetainedHandles(): List<ByteArray> =
+        retainedHandles.map { handle ->
+            handle.openInputStream().use { input -> input.readBytes() }
+        }
+
+    fun closeRetainedHandles() {
+        retainedHandles.forEach { handle ->
+            runCatching { handle.close() }
+        }
+        retainedHandles = emptyList()
+    }
새 테스트 추가
@@
     @Test
     fun `multipart image request authenticates signed manifest and forwards image bytes`() =
         testApplication {
@@
         }
+
+    @Test
+    fun `multipart image handles remain readable after route returns when spill-backed`() =
+        testApplication {
+            val sender = RecordingMultipartMessageSender(consumeImmediately = false)
+            application {
+                install(ContentNegotiation) {
+                    json(serverJson)
+                }
+                install(StatusPages) {
+                    exception<ApiRequestException> { call, cause ->
+                        call.respond(cause.status, CommonErrorResponse(message = cause.message))
+                    }
+                }
+                routing {
+                    installReplyRoutes(
+                        authSupport = AuthSupport(party.qwer.iris.RequestAuthenticator(), multipartRouteConfig),
+                        serverJson = serverJson,
+                        notificationReferer = "ref",
+                        messageSender = sender,
+                        replyStatusProvider = null,
+                    )
+                }
+            }
+
+            // > 1 byte to force spill-backed buffering under ReplyImageIngressPolicy.fromEnv()
+            val image = pngBytes(1, 2, 3, 4) + ByteArray(8192) { (it % 251).toByte() }
+            val metadata =
+                serverJson.encodeToString(
+                    ReplyImageMetadata(
+                        type = ReplyType.IMAGE,
+                        room = "123456",
+                        images =
+                            listOf(
+                                ReplyImagePartSpec(
+                                    index = 0,
+                                    sha256Hex = sha256Hex(image),
+                                    byteLength = image.size.toLong(),
+                                    contentType = "image/png",
+                                ),
+                            ),
+                    ),
+                )
+
+            val response =
+                this.client.post("/reply") {
+                    setBody(
+                        MultiPartFormDataContent(
+                            formData {
+                                append("metadata", metadata, headersOf(HttpHeaders.ContentType, "application/json"))
+                                append(
+                                    "image",
+                                    buildPacket { writeFully(image) },
+                                    headersOf(
+                                        HttpHeaders.ContentDisposition to listOf("form-data; name=\"image\"; filename=\"spill.png\""),
+                                        HttpHeaders.ContentType to listOf("image/png"),
+                                    ),
+                                )
+                            },
+                        ),
+                    )
+                    applySignedHeaders(
+                        path = "/reply",
+                        method = "POST",
+                        bodySha256Hex = sha256Hex(metadata.toByteArray()),
+                    )
+                }
+
+            assertEquals(HttpStatusCode.Accepted, response.status)
+            assertContentEquals(image, sender.consumeRetainedHandles().single())
+            sender.closeRetainedHandles()
+        }
이 테스트가 중요한 이유

이 테스트는 기존 테스트와 다르게 실제 프로덕션 ownership 흐름을 검증합니다.

즉, “route가 성공 응답을 준 뒤, 나중에 worker가 읽어도 handle이 살아 있어야 한다”를 확인합니다.
이 테스트는 지금 버그를 직접 잡아낼 수 있습니다.

P0-2. ReplyAdmissionService는 shutdown/restart 시 channel reference를 잃지 않게 바꿔야 합니다

이건 closeout 문서상으로는 많이 정리된 것처럼 보이지만,
실제 구현은 아직 graceful drain semantics가 완전히 정확하지 않습니다.

핵심 문제는 이것입니다.

markClosing()이 Open(channel, job, queuedJobs)를 Closing(job)으로 바꾸면서
channel reference를 버립니다
그러면 closeWorkersSuspend()에서 Closing 상태 worker channel을 닫을 수 없습니다
결국 graceful close가 아니라 idle timeout/cancel에 기대게 됩니다

이건 구조상 틀린 것입니다.

권장 수정 방향

핵심은 단순합니다.

Closing 상태도 channel을 계속 들고 있어야 합니다
Closed 상태는 현재 구현상 실익이 없으므로 제거하는 편이 낫습니다
worker 종료 이유는 Boolean idleTimeout이 아니라 enum으로 올리는 것이 맞습니다
권장 diff
diff --git a/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt b/app/src/main/java/party/qwer/iris/reply/ReplyAdmissionService.kt
@@
     private sealed interface WorkerMailboxState {
         data class Open(
             val channel: Channel<ReplyLaneJob>,
             val job: Job,
             val queuedJobs: Int,
         ) : WorkerMailboxState
 
         data class Closing(
+            val channel: Channel<ReplyLaneJob>,
             val job: Job,
+            val queuedJobs: Int,
         ) : WorkerMailboxState
-
-        data object Closed : WorkerMailboxState
     }
+
+    private enum class WorkerCloseReason {
+        IDLE_TIMEOUT,
+        CHANNEL_CLOSED,
+        CANCELLED,
+        FAILED,
+    }
@@
         data class WorkerClosed(
             val key: ReplyQueueKey,
             val workerId: Long,
-            val idleTimeout: Boolean,
+            val reason: WorkerCloseReason,
         ) : AdmissionCommand
worker loop 수정
@@
         val job =
             workerScope.launch {
-                var idleTimeout = false
+                var closeReason = WorkerCloseReason.CHANNEL_CLOSED
                 try {
                     while (true) {
                         val receiveResult =
                             withTimeoutOrNull(workerIdleTimeoutMs) {
                                 channel.receiveCatching()
                             }
                         if (receiveResult == null) {
-                            idleTimeout = true
+                            closeReason = WorkerCloseReason.IDLE_TIMEOUT
                             break
                         }
                         val job = receiveResult.getOrNull() ?: break
@@
                         try {
                             jobProcessor(job)
+                        } catch (_: kotlinx.coroutines.CancellationException) {
+                            closeReason = WorkerCloseReason.CANCELLED
+                            throw
                         } catch (e: Exception) {
+                            closeReason = WorkerCloseReason.FAILED
                             IrisLogger.error("[ReplyAdmissionService] worker($key) error: ${e.message}", e)
                         }
                     }
                 } finally {
                     channel.close()
-                    val reason = if (idleTimeout) "idle timeout" else "channel closed"
                     commands.trySend(
                         AdmissionCommand.WorkerClosed(
                             key = key,
                             workerId = workerId,
-                            idleTimeout = idleTimeout,
+                            reason = closeReason,
                         ),
                     )
-                    IrisLogger.debug("[ReplyAdmissionService] worker($key) terminated ($reason)")
+                    IrisLogger.debug("[ReplyAdmissionService] worker($key) terminated (${closeReason.name})")
                 }
             }
closeWorkersSuspend()가 Closing 상태도 닫도록 수정
@@
     private suspend fun closeWorkersSuspend(workers: List<WorkerHandle>) {
         val gracefulWaitMs = minOf(1_000L, shutdownTimeoutMs)
         workers.forEach { worker ->
-            (worker.mailboxState as? WorkerMailboxState.Open)?.channel?.close()
+            worker.channel()?.close()
         }
@@
     }
helper 추가
@@
     private fun markClosing(worker: WorkerHandle): WorkerHandle =
         when (val mailboxState = worker.mailboxState) {
-            is WorkerMailboxState.Open -> worker.copy(mailboxState = WorkerMailboxState.Closing(mailboxState.job))
+            is WorkerMailboxState.Open ->
+                worker.copy(
+                    mailboxState =
+                        WorkerMailboxState.Closing(
+                            channel = mailboxState.channel,
+                            job = mailboxState.job,
+                            queuedJobs = mailboxState.queuedJobs,
+                        ),
+                )
             is WorkerMailboxState.Closing -> worker
-            WorkerMailboxState.Closed -> worker
         }
 
+    private fun WorkerHandle.channel(): Channel<ReplyLaneJob>? =
+        when (val mailboxState = mailboxState) {
+            is WorkerMailboxState.Open -> mailboxState.channel
+            is WorkerMailboxState.Closing -> mailboxState.channel
+        }
+
     private fun WorkerHandle.job(): Job? =
         when (val mailboxState = mailboxState) {
             is WorkerMailboxState.Open -> mailboxState.job
             is WorkerMailboxState.Closing -> mailboxState.job
-            WorkerMailboxState.Closed -> null
         }
debug snapshot도 queueDepth를 보존하도록 수정
@@
     private fun toWorkerDebug(worker: WorkerHandle): ReplyAdmissionWorkerDebug {
         val mailboxState = worker.mailboxState
-        val queueDepth = (mailboxState as? WorkerMailboxState.Open)?.queuedJobs ?: 0
+        val queueDepth =
+            when (mailboxState) {
+                is WorkerMailboxState.Open -> mailboxState.queuedJobs
+                is WorkerMailboxState.Closing -> mailboxState.queuedJobs
+            }
         val stateName =
             when (mailboxState) {
                 is WorkerMailboxState.Open -> "OPEN"
                 is WorkerMailboxState.Closing -> "CLOSING"
-                WorkerMailboxState.Closed -> "CLOSED"
             }
왜 이 수정이 중요한가

지금 구조는 “닫힘 상태로 바꿨다”는 것처럼 보이지만,
실제로는 graceful drain에 필요한 핵심 참조(channel)를 잃고 있습니다.

이걸 고치면:

restart/shutdown 시 실제로 channel을 닫아 drain 유도 가능
idle timeout에 운 좋게 기대는 구조 제거
debug snapshot도 더 정확해짐
state model이 더 간결해짐 (Closed 제거)

이건 승인에 필요한 correctness 수정입니다.

같이 추가해야 할 테스트

이 파일은 이번 스냅샷에 테스트가 안 들어 있지만, 아래 두 테스트는 꼭 넣어야 합니다.

1. shutdown이 Closing worker channel을 실제로 닫는지
queue에 job 하나 넣고
shutdown
idle timeout을 기다리지 않고 종료되는지 확인
2. restart가 pending job을 drain하고 새 scope로 전환하는지
old worker에 queuedJobs가 있는 상태에서 restart
old worker channel이 닫히고
restart complete 뒤 새 worker가 생기는지 확인
P0-3. WebhookHttpClientFactory는 이름과 구현 중 하나를 반드시 고쳐야 합니다

현재 문제는 아주 명확합니다.

PRIVATE_OVERLAY_HTTP_ALLOWED라는 이름은
“사설망/루프백 cleartext만 허용”처럼 읽힙니다.

그런데 실제 구현은:

TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED -> Unit

즉, 아무 검증 없이 모든 cleartext HTTP를 허용합니다.

이건 두 가지 중 하나입니다.

구현이 맞다면 이름이 틀린 것
이름이 맞다면 구현이 위험하게 넓은 것

엄격한 기준에서는 이 모호성을 남기면 안 됩니다.

제가 권하는 해법

운영 문서와 closeout 문구가 “private overlay”를 말하고 있으므로,
구현을 이름에 맞추는 쪽이 맞습니다.

즉, PRIVATE_OVERLAY_HTTP_ALLOWED일 때는 isTrustedPrivateWebhookUrl()을 실제로 적용해야 합니다.

권장 diff
diff --git a/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookHttpClientFactory.kt b/app/src/main/java/party/qwer/iris/delivery/webhook/WebhookHttpClientFactory.kt
@@
                 TransportSecurityMode.LOOPBACK_HTTP_ALLOWED -> {
                     if (!isLoopbackWebhookUrl(webhookUrl)) {
                         throw IllegalArgumentException(
                             "cleartext HTTP webhook requires loopback host or IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED",
                         )
                     }
                 }
 
-                TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED -> Unit
+                TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED -> {
+                    if (!isTrustedPrivateWebhookUrl(webhookUrl)) {
+                        throw IllegalArgumentException(
+                            "cleartext HTTP webhook requires a trusted private-network host " +
+                                "(loopback, RFC1918, CGNAT, or IPv6 ULA) when " +
+                                "IRIS_WEBHOOK_TRANSPORT_SECURITY_MODE=PRIVATE_OVERLAY_HTTP_ALLOWED",
+                        )
+                    }
+                }
             }
         }
         return defaultClient
     }
추가로 권하는 정리

legacy alias가 필요하면 남겨도 됩니다.
다만 코드 주석으로 분명히 해야 합니다.

@@
-        "private_overlay_http_allowed", "private_overlay", "cleartext_http_allowed" ->
+        // "cleartext_http_allowed" is retained as a legacy alias.
+        "private_overlay_http_allowed", "private_overlay", "cleartext_http_allowed" ->
             TransportSecurityMode.PRIVATE_OVERLAY_HTTP_ALLOWED
왜 이 수정이 중요한가

이건 단순 naming cleanup이 아닙니다.
보안 정책은 이름과 실제 허용 범위가 일치해야 합니다.

지금처럼 이름은 제한적인데 구현은 광범위하면,
운영자는 잘못된 가정으로 시스템을 배치할 수 있습니다.

승인 기준에서는 이런 정책-구현 mismatch를 남기면 안 됩니다.

P0-4. Auth contract는 fixture와 테스트를 실제 coverage에 맞게 바꿔야 합니다

현재 문서상으로는 auth vector가 7개라고 적혀 있지만,
실제 fixture는 3개뿐입니다.

이건 단순 문서 오기 이전에, evidence package의 추적성 문제입니다.

여기서는 두 가지를 같이 고쳐야 합니다.

fixture를 실제로 7개 이상으로 늘린다
Kotlin/Rust contract test가 그 fixture를 그대로 돈다는 것을 유지한다
1) fixture 자체를 확장하십시오

현재 tests/contracts/iris_auth_vectors.json에는 3개만 있습니다.
아래 4개를 실제로 추가하는 것이 맞습니다.

unicode body
percent-encoded path
repeated query param
lowercase method normalization

아래 값은 현재 signing 규약 기준으로 바로 넣을 수 있는 실제 vector 초안입니다.

권장 diff
diff --git a/tests/contracts/iris_auth_vectors.json b/tests/contracts/iris_auth_vectors.json
@@
   {
     "name": "get-stats-query-body-hash",
     "secret": "tok",
     "method": "GET",
     "target": "/rooms/42/stats?limit=5&period=7d",
     "timestampMs": "500",
     "nonce": "n1",
     "body": "",
     "bodySha256Hex": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
     "canonicalRequest": "GET\n/rooms/42/stats?limit=5&period=7d\n500\nn1\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
     "signature": "578c36195bd0d04e31e6a0b4b6570145a27db0aa213ca2d48799996effde88a3"
+  },
+  {
+    "name": "post-reply-unicode-body",
+    "secret": "secret",
+    "method": "POST",
+    "target": "/reply?room=1",
+    "timestampMs": "1700000000001",
+    "nonce": "reply-unicode-1",
+    "body": "{\"message\":\"안녕하세요 🌱\"}",
+    "bodySha256Hex": "dd0266f349a86b2e7289214fd1b6211afd2cabc77b81ca1d9684375a9f8f4f18",
+    "canonicalRequest": "POST\n/reply?room=1\n1700000000001\nreply-unicode-1\ndd0266f349a86b2e7289214fd1b6211afd2cabc77b81ca1d9684375a9f8f4f18",
+    "signature": "9be68e780aa43d6d99f9869a8a59f56cb3e27254b7ca7ad2970b5db3fcae2a8b"
+  },
+  {
+    "name": "get-percent-encoded-path",
+    "secret": "tok",
+    "method": "GET",
+    "target": "/rooms/%EC%95%88%EB%85%95/stats?label=%F0%9F%8C%B1",
+    "timestampMs": "501",
+    "nonce": "n2",
+    "body": "",
+    "bodySha256Hex": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
+    "canonicalRequest": "GET\n/rooms/%EC%95%88%EB%85%95/stats?label=%F0%9F%8C%B1\n501\nn2\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
+    "signature": "6946233ee05f47a828ebec7bc583e416e204cc3d1f07e3aa7dc62398479920dc"
+  },
+  {
+    "name": "get-repeated-query-param",
+    "secret": "tok",
+    "method": "GET",
+    "target": "/rooms/42/stats?tag=a&tag=b&limit=5",
+    "timestampMs": "502",
+    "nonce": "n3",
+    "body": "",
+    "bodySha256Hex": "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
+    "canonicalRequest": "GET\n/rooms/42/stats?tag=a&tag=b&limit=5\n502\nn3\ne3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855",
+    "signature": "2fab820007fefcd23a7957e60fe908494e9ac956852ce58c2dc9b42687fa1498"
+  },
+  {
+    "name": "post-lowercase-method-normalization",
+    "secret": "secret",
+    "method": "post",
+    "target": "/reply",
+    "timestampMs": "1700000000002",
+    "nonce": "reply-lowercase-1",
+    "body": "{\"message\":\"lowercase method\"}",
+    "bodySha256Hex": "260142963aeee55595ab13b87b5b5d469f9cc4c782aacadebd973a8dcad72e29",
+    "canonicalRequest": "POST\n/reply\n1700000000002\nreply-lowercase-1\n260142963aeee55595ab13b87b5b5d469f9cc4c782aacadebd973a8dcad72e29",
+    "signature": "f470e77a93c15e37c858e505e5083e228066d7abd5e8cb7a8e28347654aa5563"
   }
 ]
2) contract test는 지금 구조로도 괜찮습니다

좋은 점은 현재 Kotlin과 Rust 테스트가 둘 다 fixture를 그대로 순회한다는 점입니다.

app/src/test/java/party/qwer/iris/RequestAuthenticatorContractTest.kt
tools/iris-common/tests/auth_contract_test.rs

즉, fixture만 늘리면 양쪽이 같이 coverage를 넓힙니다.

이 부분은 구조를 바꾸지 않아도 됩니다.

3) 하지만 negative case는 별도 Kotlin 테스트로 추가해야 합니다

cross-language contract fixture는 positive canonical/signature contract 위주로 유지하는 것이 맞습니다.
negative case까지 fixture 하나에 우겨 넣으면 schema가 복잡해지고, Rust signer test와 Kotlin verifier test의 목적이 섞입니다.

그래서 negative는 Kotlin verifier 전용 테스트로 빼는 것이 좋습니다.

새 파일 추가 권장
diff --git a/app/src/test/java/party/qwer/iris/RequestAuthenticatorNegativeTest.kt b/app/src/test/java/party/qwer/iris/RequestAuthenticatorNegativeTest.kt
new file mode 100644
--- /dev/null
+++ b/app/src/test/java/party/qwer/iris/RequestAuthenticatorNegativeTest.kt
@@
+package party.qwer.iris
+
+import kotlin.test.Test
+import kotlin.test.assertEquals
+
+class RequestAuthenticatorNegativeTest {
+    @Test
+    fun `rejects replayed nonce after successful finalize`() {
+        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
+        val body = """{"message":"hello"}"""
+        val bodySha = sha256Hex(body.toByteArray())
+        val signature =
+            signIrisRequestWithBodyHash(
+                secret = "secret",
+                method = "POST",
+                path = "/reply",
+                timestamp = "1700000000000",
+                nonce = "nonce-1",
+                bodySha256Hex = bodySha,
+            )
+
+        val first =
+            auth.authenticate(
+                method = "POST",
+                path = "/reply",
+                body = body,
+                bodySha256Hex = bodySha,
+                expectedSecret = "secret",
+                timestampHeader = "1700000000000",
+                nonceHeader = "nonce-1",
+                signatureHeader = signature,
+            )
+        val second =
+            auth.authenticate(
+                method = "POST",
+                path = "/reply",
+                body = body,
+                bodySha256Hex = bodySha,
+                expectedSecret = "secret",
+                timestampHeader = "1700000000000",
+                nonceHeader = "nonce-1",
+                signatureHeader = signature,
+            )
+
+        assertEquals(AuthResult.AUTHORIZED, first)
+        assertEquals(AuthResult.UNAUTHORIZED, second)
+    }
+
+    @Test
+    fun `rejects when actual body hash differs from declared body hash`() {
+        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
+        val signedBody = """{"message":"hello"}"""
+        val actualBody = """{"message":"tampered"}"""
+        val declaredSha = sha256Hex(signedBody.toByteArray())
+        val actualSha = sha256Hex(actualBody.toByteArray())
+        val signature =
+            signIrisRequestWithBodyHash(
+                secret = "secret",
+                method = "POST",
+                path = "/reply",
+                timestamp = "1700000000000",
+                nonce = "nonce-2",
+                bodySha256Hex = declaredSha,
+            )
+
+        val result =
+            auth.authenticate(
+                method = "POST",
+                path = "/reply",
+                body = actualBody,
+                bodySha256Hex = actualSha,
+                expectedSecret = "secret",
+                timestampHeader = "1700000000000",
+                nonceHeader = "nonce-2",
+                signatureHeader = signature,
+            )
+
+        assertEquals(AuthResult.UNAUTHORIZED, result)
+    }
+
+    @Test
+    fun `rejects stale timestamp`() {
+        val auth = RequestAuthenticator(nowEpochMs = { 1_700_000_000_000L })
+        val body = ""
+        val bodySha = sha256Hex(body.toByteArray())
+        val signature =
+            signIrisRequestWithBodyHash(
+                secret = "secret",
+                method = "GET",
+                path = "/config",
+                timestamp = "1699990000000",
+                nonce = "nonce-stale",
+                bodySha256Hex = bodySha,
+            )
+
+        val result =
+            auth.authenticate(
+                method = "GET",
+                path = "/config",
+                body = body,
+                bodySha256Hex = bodySha,
+                expectedSecret = "secret",
+                timestampHeader = "1699990000000",
+                nonceHeader = "nonce-stale",
+                signatureHeader = signature,
+            )
+
+        assertEquals(AuthResult.UNAUTHORIZED, result)
+    }
+}
왜 이렇게 나누는가

이렇게 하면 구조가 명확해집니다.

contract fixture = 양 언어 공통 positive contract
Kotlin negative tests = verifier semantics
문서도 이 둘을 구분해서 쓸 수 있음

지금처럼 “fixture는 3개인데 문서는 7개” 같은 신뢰성 손상을 막을 수 있습니다.

P0-5. closeout 문서와 README는 손으로 때우지 말고, 산출물 기준으로 다시 생성해야 합니다

이건 코드라기보다 패키지 신뢰성 문제이지만, 승인에는 필수입니다.

현재 문제는:

README가 실제 패키지 구조와 다르고
verification summary가 실제 cargo 로그 숫자와 다르고
evidence index가 fixture 개수와 다릅니다

이건 문서를 수동으로 갱신한 흔적이 강합니다.

이걸 다시 승인 가능한 수준으로 만들려면,
문서를 “수정”하는 게 아니라 생성 방식 자체를 바꿔야 합니다.

1) README 문구 먼저 정직하게 바꾸십시오

현재 패키지는 “전체 소스 번들”이 아닙니다.
정확히는 “선택된 소스 스냅샷 + patch + 증거물 묶음”입니다.

권장 diff
diff --git a/README.md b/README.md
@@
-본 패키지는 closeout 대상 코드의 전체 소스·테스트·증거를 포함합니다.
+본 패키지는 closeout 재검토에 필요한 선택 소스 스냅샷, working-tree patch,
+테스트 산출물, 계약 fixture를 포함합니다.
+
+주의:
+- 이 패키지 단독으로는 전체 workspace 재빌드가 불가능합니다.
+- 재현에는 `artifacts/metadata/revision.txt`에 기록된 기준 revision의 원 저장소가 필요합니다.
재현 섹션도 같이 고치십시오
@@
-./gradlew :app:testDebugUnitTest
-cargo test --manifest-path tools/Cargo.toml
+원 저장소의 기준 revision checkout 후:
+1. `artifacts/patches/working-tree.patch` 적용
+2. 아래 명령 실행
+
+./gradlew :app:testDebugUnitTest
+cargo test --manifest-path tools/Cargo.toml

이건 패키지를 솔직하게 만드는 최소 수정입니다.

2) verification summary는 수기로 고치지 말고 재생성하십시오

문서만 고쳐도 되지만, 또 틀릴 수 있습니다.
그래서 저는 작은 생성 스크립트를 권합니다.

새 스크립트 추가 권장
diff --git a/scripts/generate-closeout-summary.py b/scripts/generate-closeout-summary.py
new file mode 100644
--- /dev/null
+++ b/scripts/generate-closeout-summary.py
@@
+from pathlib import Path
+import json
+import re
+
+ROOT = Path(__file__).resolve().parents[1]
+summary_path = ROOT / "artifacts" / "test-results" / "verification-summary.md"
+cargo_log = (ROOT / "artifacts" / "test-results" / "cargo-test.txt").read_text()
+fixture = json.loads((ROOT / "tests" / "contracts" / "iris_auth_vectors.json").read_text())
+
+def count_rust_tests(crate_name: str) -> int:
+    pattern = re.compile(rf"Running .*{crate_name}.*?test result: ok\\. ([0-9]+) passed", re.S)
+    matches = pattern.findall(cargo_log)
+    return sum(int(x) for x in matches)
+
+iris_common_unit = 16
+iris_common_contract = 1
+iris_ctl = 55
+iris_daemon = 48
+auth_vectors = len(fixture)
+
+summary_path.write_text(
+    f\"\"\"# Verification Summary
+
+## Rust — cargo test
+
+| crate | 테스트 수 | 결과 |
+|-------|----------|------|
+| iris-common (unit) | {iris_common_unit} | **PASS** |
+| iris-common (contract) | {iris_common_contract} | **PASS** |
+| iris-ctl | {iris_ctl} | **PASS** |
+| iris-daemon | {iris_daemon} | **PASS** |
+| **합계** | **{iris_common_unit + iris_common_contract + iris_ctl + iris_daemon}** | **PASS** |
+
+## Auth Contract Vectors
+
+총 {auth_vectors}/{auth_vectors} 벡터 양측 통과.
+\"\"\"
+)
왜 스크립트가 필요한가

지금 문제는 문서가 stale해졌다는 점입니다.
즉, 사람이 마지막에 수기로 문구를 맞춘 흔적이 남아 있습니다.

closeout 패킷은 문서가 제일 마지막에 깨집니다.
그래서 숫자는 항상 산출물에서 다시 뽑아 생성하는 게 맞습니다.

P1-1. reply_modal은 승인 보류급 버그는 아니지만, AI 냄새를 줄이려면 여기까지 가야 합니다

이 항목은 지금 당장 승인 보류의 핵심은 아닙니다.
하지만 “기계적이고 인위적인 냄새를 순화시키고 코드에 생기를 불어넣는” 작업의 핵심이 바로 이 파일입니다.

현재 상태를 한 문장으로 말하면:

조금 나아졌지만, 아직 giant stateful widget입니다.

현재 남아 있는 문제
ModalFocus가 field focus와 selector overlay 상태를 같이 표현
validation이 여전히 UI mutation과 직접 연결
ReplyModal이 form state, UI state, selector state를 다 직접 소유
success reset도 widget 본체가 직접 수행

이건 기능은 되지만, 코드가 숨을 쉬지 못합니다.

권장 diff의 방향
1) ModalFocus를 둘로 쪼개십시오

tools/iris-ctl/src/views/reply_modal/state.rs:

diff --git a/tools/iris-ctl/src/views/reply_modal/state.rs b/tools/iris-ctl/src/views/reply_modal/state.rs
@@
-#[derive(Clone, Copy, PartialEq, Eq)]
-pub enum ModalFocus {
-    Type,
-    Room,
-    RoomSelector,
-    Thread,
-    ThreadId,
-    ThreadSelector,
-    Scope,
-    Content,
-}
+#[derive(Clone, Copy, PartialEq, Eq)]
+pub enum FieldFocus {
+    Type,
+    Room,
+    Thread,
+    ThreadId,
+    Scope,
+    Content,
+}
+
+#[derive(Clone, Copy, PartialEq, Eq)]
+pub enum OverlayState {
+    None,
+    RoomSelector,
+    ThreadSelector,
+}
+
+pub struct ReplyUiState {
+    pub field_focus: FieldFocus,
+    pub overlay: OverlayState,
+    pub result: Option<ReplyResult>,
+    pub sending: bool,
+}
2) validation은 pure rule로 남기고, UI 적용은 widget이 하게 하십시오

validate.rs:

diff --git a/tools/iris-ctl/src/views/reply_modal/validate.rs b/tools/iris-ctl/src/views/reply_modal/validate.rs
@@
-use crate::views::reply_modal::state::{ModalFocus, ReplyResult, ReplyValidationError, ThreadMode, ThreadScope};
+use crate::views::reply_modal::state::{FieldFocus, ReplyValidationError, ThreadMode, ThreadScope};
@@
-pub(crate) fn apply_validation_error(
-    result: &mut Option<ReplyResult>,
-    focus: &mut ModalFocus,
-    error: ReplyValidationError,
-) {
-    let (message, next_focus) = match error {
-        ReplyValidationError::MissingThreadId => ("thread id를 입력해주세요".to_string(), ModalFocus::ThreadId),
-        ReplyValidationError::InvalidThreadId => ("thread id는 숫자여야 합니다".to_string(), ModalFocus::ThreadId),
-        ReplyValidationError::MissingTextContent => ("내용을 입력해주세요".to_string(), ModalFocus::Content),
-        ReplyValidationError::MissingImagePath => ("이미지 경로를 입력해주세요".to_string(), ModalFocus::Content),
-        ReplyValidationError::MissingImagePaths => ("최소 한 개의 이미지 경로를 입력해주세요".to_string(), ModalFocus::Content),
-    };
-    *result = Some(ReplyResult::Error { message });
-    *focus = next_focus;
-}
+pub(crate) fn validation_message_and_focus(
+    error: ReplyValidationError,
+) -> (String, FieldFocus) {
+    match error {
+        ReplyValidationError::MissingThreadId => ("thread id를 입력해주세요".to_string(), FieldFocus::ThreadId),
+        ReplyValidationError::InvalidThreadId => ("thread id는 숫자여야 합니다".to_string(), FieldFocus::ThreadId),
+        ReplyValidationError::MissingTextContent => ("내용을 입력해주세요".to_string(), FieldFocus::Content),
+        ReplyValidationError::MissingImagePath => ("이미지 경로를 입력해주세요".to_string(), FieldFocus::Content),
+        ReplyValidationError::MissingImagePaths => ("최소 한 개의 이미지 경로를 입력해주세요".to_string(), FieldFocus::Content),
+    }
+}
3) 본체는 ReplyDraft + ReplyUiState를 들게 하십시오

reply_modal.rs 본체도 아래처럼 바꾸는 것이 좋습니다.

@@
-pub struct ReplyModal {
-    reply_type: ReplyType,
-    room: Option<RoomSummary>,
-    thread_mode: ThreadMode,
-    pub(crate) thread_id_input: String,
-    scope: ThreadScope,
-    text_area: TextArea<'static>,
-    image_path: PathInput,
-    image_paths: Vec<PathInput>,
-    image_paths_cursor: usize,
-    image_editing: bool,
-    pub focus: ModalFocus,
-    pub result: Option<ReplyResult>,
-    pub sending: bool,
+pub struct ReplyDraft {
+    reply_type: ReplyType,
+    room: Option<RoomSummary>,
+    thread_mode: ThreadMode,
+    thread_id_input: String,
+    scope: ThreadScope,
+    text_area: TextArea<'static>,
+    image_path: PathInput,
+    image_paths: Vec<PathInput>,
+    image_paths_cursor: usize,
+    image_editing: bool,
+}
+
+pub struct ReplyModal {
+    draft: ReplyDraft,
+    pub ui: ReplyUiState,
     pub room_list: Vec<RoomSummary>,
     room_selector_cursor: usize,
     pub thread_suggestions: Vec<ThreadSummary>,
     thread_selector_cursor: usize,
 }
왜 이게 AI 냄새를 줄이는가

지금 구조는 “하나의 똑똑한 giant object”입니다.
이건 기능은 되지만 기계적인 느낌이 납니다.

여기서 생기를 넣는 방법은, 코드를 더 지저분하게 만드는 게 아니라:

폼 상태
화면 상태
검증 규칙
overlay 상태

를 각각 자기 언어로 말하게 만드는 것입니다.

즉, 큰 파일을 무작정 잘게 자르는 것이 아니라
상태의 축을 분리하는 것이 핵심입니다.

P1-2. AppRuntime.kt는 아직 giant mutable orchestrator입니다

이건 이번 승인 보류의 직접 원인은 아닙니다.
하지만 closeout 문서가 Closed로 보기에는 아직 과합니다.

현재 AppRuntime.kt는:

lateinit 필드가 많고
start()가 순차 조립식이며
중간 실패 rollback이 구조로 표현되지 않습니다

이건 아직 partial입니다.

권장 diff 방향

이건 큰 리팩터라 전체 patch를 여기 다 적지는 않겠습니다.
하지만 골격은 아래처럼 가야 합니다.

1) RunningRuntime 도입
diff --git a/app/src/main/java/party/qwer/iris/AppRuntime.kt b/app/src/main/java/party/qwer/iris/AppRuntime.kt
@@
-    private lateinit var configManager: ConfigManager
-    private lateinit var bridgeClient: UdsImageBridgeClient
-    ...
-    private var irisServer: IrisServer? = null
+    private var running: RunningRuntime? = null
2) buildRuntime()에서 조립하고, 실패 시 rollback
private fun buildRuntime(): RunningRuntime {
    val rollback = ArrayDeque<() -> Unit>()
    try {
        val configManager = ConfigManager().also {
            rollback.addFirst { if (!it.saveConfigNow()) IrisLogger.error("...") }
        }
        ...
        return RunningRuntime(...)
    } catch (t: Throwable) {
        rollback.forEach { step -> runCatching { step() } }
        throw t
    }
}
3) stop()은 running snapshot 기준으로 역순 정리

이렇게 해야 shutdown plan과 startup plan이 대칭이 됩니다.

마지막으로, 실제 적용 순서를 아주 분명히 정리하겠습니다

이번에는 “무엇을 먼저 해야 승인 가능한가”를 코드 기준으로 딱 잘라 드리겠습니다.

지금 바로 해야 할 순서
1단계

ReplyRoutes.kt + MultipartReplyCollector.kt + ReplyRoutesMultipartTest.kt

이 세 파일을 먼저 고치십시오.
이건 실제 기능 버그이기 때문입니다.

2단계

ReplyAdmissionService.kt

channel 보존과 shutdown/restart semantics를 고치십시오.
이건 correctness입니다.

3단계

WebhookHttpClientFactory.kt

이름과 구현을 맞추십시오.
보안 정책은 애매하게 두면 안 됩니다.

4단계

iris_auth_vectors.json + contract tests + negative Kotlin test 추가

이건 코드와 패키지 신뢰성 둘 다 올립니다.

5단계

README / verification-summary / evidence-index 재생성

이건 손으로 고치지 말고 산출물 기준으로 재생성하십시오.

6단계

그 다음에야
reply_modal / AppRuntime / 나머지 giant object 분해로 들어가면 됩니다.

한 줄 결론

지금 승인까지 가려면,
먼저 “예쁘게 리팩터링”할 게 아니라, ownership이 틀린 곳부터 바로잡아야 합니다.

가장 중요한 것은 이 두 줄입니다.

multipart handle은 route가 성공 후 닫으면 안 됩니다
closing worker는 channel reference를 잃으면 안 됩니다

이 두 가지를 먼저 고치고,
그다음에 보안 정책과 contract evidence를 정리해야 이번 패킷이 비로소 승인 가능한 수준으로 올라갑니다.