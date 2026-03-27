# LSPosed Image Bridge Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Replace the broken shell-native image sender with an LSPosed module that sends Kakao images from inside `com.kakao.talk` via abstract namespace UDS IPC.

**Architecture:** Iris keeps its existing `/reply-image` route, queue, and image preparation pipeline unchanged. A new `UdsImageReplySender` replaces `KakaoNativeImageReplySender` and sends prepared image file paths over an abstract namespace UDS socket to a bridge module. The bridge module is a separate APK installed as an LSPosed module, runs inside `com.kakao.talk`'s process, receives paths via UDS, and calls Kakao's internal `ChatMediaSender` with proper caller identity — resolving the shell-native SecurityException blocker.

**Tech Stack:** Kotlin, Android `LocalSocket`/`LocalServerSocket` (abstract namespace UDS), Xposed API 82 (compile-only), `org.json`

---

## File Structure

### App module — Iris side (`:app`)

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `app/src/main/java/party/qwer/iris/ImageBridgeProtocol.kt` | UDS protocol constants and frame codec |
| Create | `app/src/main/java/party/qwer/iris/UdsImageBridgeClient.kt` | UDS client — connects, sends request, reads response |
| Create | `app/src/main/java/party/qwer/iris/UdsImageReplySender.kt` | `NativeImageReplySender` impl that delegates to UDS client |
| Create | `app/src/test/java/party/qwer/iris/ImageBridgeProtocolTest.kt` | Protocol frame codec unit tests |
| Modify | `app/src/main/java/party/qwer/iris/Main.kt:24` | Wire `UdsImageReplySender` instead of default |

### Bridge module — LSPosed side (`:bridge`)

| Action | File | Responsibility |
|--------|------|---------------|
| Create | `bridge/build.gradle.kts` | Android application module with Xposed API |
| Create | `bridge/src/main/AndroidManifest.xml` | Xposed module metadata |
| Create | `bridge/src/main/assets/xposed_init` | Xposed entry class declaration |
| Create | `bridge/src/main/java/party/qwer/iris/bridge/IrisBridgeModule.kt` | `IXposedHookLoadPackage` — hooks Application.onCreate, starts server |
| Create | `bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeServer.kt` | UDS server — accept, dispatch, respond |
| Create | `bridge/src/main/java/party/qwer/iris/bridge/KakaoImageSender.kt` | In-process Kakao image sender via reflection |

### Build config

| Action | File | Change |
|--------|------|--------|
| Modify | `settings.gradle.kts:23` | `include(":bridge")` |
| Create | `bridge/libs/api-82.jar` | Xposed Bridge API (compile-only, not committed) |

---

## UDS Protocol Specification

- **Socket:** abstract namespace `iris-image-bridge`
- **Framing:** 4-byte big-endian length prefix + UTF-8 JSON payload
- **Max frame:** 1 MB
- **Lifecycle:** connection-per-request (connect → send → recv → close)

**Request:**
```json
{
  "action": "send_image",
  "roomId": 18478615493603057,
  "imagePaths": ["/sdcard/Android/data/com.kakao.talk/files/iris-outbox-images/abc.png"],
  "threadId": 3804936595914842113,
  "threadScope": 2
}
```

**Success response:**
```json
{"status": "sent"}
```

**Failure response:**
```json
{"status": "failed", "error": "chat room not found: 12345"}
```

---

### Task 1: Bridge Module Scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Create: `bridge/build.gradle.kts`
- Create: `bridge/src/main/AndroidManifest.xml`
- Create: `bridge/src/main/assets/xposed_init`

- [ ] **Step 1: Add bridge module to settings.gradle.kts**

```kotlin
// settings.gradle.kts — add after line 23
include(":bridge")
```

The full file becomes:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Iris"
include(":app")
include(":bridge")
```

- [ ] **Step 2: Create bridge/build.gradle.kts**

```kotlin
import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "party.qwer.iris.bridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "party.qwer.iris.bridge"
        minSdk = 35
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        disable += setOf("PrivateApi", "SdCardPath")
        disable += setOf(
            "GradleDependency",
            "NewerVersionAvailable",
            "AndroidGradlePluginVersion",
            "OldTargetApi",
        )
    }
}

kotlin {
    jvmToolchain(21)
}

private fun registerAssembleOutputCopyTask(variantName: String) {
    val assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercase() }}"
    val copyTaskName = "sync${variantName.replaceFirstChar { it.uppercase() }}ApkToOutput"
    val copyTask =
        tasks.register<Copy>(copyTaskName) {
            from(layout.buildDirectory.dir("outputs/apk/$variantName"))
            include("*.apk")
            into(rootProject.layout.projectDirectory.dir("output"))
            rename { "IrisBridge-$variantName.apk" }
        }
    tasks.matching { it.name == assembleTaskName }.configureEach {
        finalizedBy(copyTask)
    }
}

registerAssembleOutputCopyTask("debug")
registerAssembleOutputCopyTask("release")

ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
}

dependencies {
    compileOnly(files("libs/api-82.jar"))
}
```

- [ ] **Step 3: Create AndroidManifest.xml**

Create `bridge/src/main/AndroidManifest.xml`:

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android">

    <application
        android:allowBackup="false"
        android:label="Iris Image Bridge">
        <meta-data
            android:name="xposedmodule"
            android:value="true" />
        <meta-data
            android:name="xposeddescription"
            android:value="Iris Kakao image sending bridge" />
        <meta-data
            android:name="xposedminversion"
            android:value="93" />
        <meta-data
            android:name="xposedscope"
            android:value="com.kakao.talk" />
    </application>
</manifest>
```

- [ ] **Step 4: Create xposed_init**

Create `bridge/src/main/assets/xposed_init`:

```
party.qwer.iris.bridge.IrisBridgeModule
```

- [ ] **Step 5: Obtain Xposed API JAR**

Download `api-82.jar` and place at `bridge/libs/api-82.jar`. This is a compile-only dependency — LSPosed provides the runtime.

```bash
mkdir -p bridge/libs
# Download from Xposed API releases or extract from an existing LSPosed installation
# The JAR provides: IXposedHookLoadPackage, XposedHelpers, XC_MethodHook, etc.
```

- [ ] **Step 6: Verify build compiles**

```bash
./gradlew :bridge:assembleDebug
```

Expected: BUILD SUCCESSFUL. Output: `output/IrisBridge-debug.apk`

- [ ] **Step 7: Commit**

```bash
git add settings.gradle.kts bridge/
git commit -m "feat(bridge): scaffold LSPosed bridge module for Kakao image sending"
```

---

### Task 2: UDS Protocol Constants and Frame Codec

**Files:**
- Create: `app/src/main/java/party/qwer/iris/ImageBridgeProtocol.kt`

- [ ] **Step 1: Create ImageBridgeProtocol.kt**

```kotlin
package party.qwer.iris

import org.json.JSONArray
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream

internal object ImageBridgeProtocol {
    const val SOCKET_NAME = "iris-image-bridge"
    const val ACTION_SEND_IMAGE = "send_image"
    const val STATUS_SENT = "sent"
    const val STATUS_FAILED = "failed"
    const val MAX_FRAME_SIZE = 1_048_576

    fun writeFrame(output: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        val dos = DataOutputStream(output)
        dos.writeInt(bytes.size)
        dos.write(bytes)
        dos.flush()
    }

    fun readFrame(input: InputStream): JSONObject {
        val dis = DataInputStream(input)
        val size = dis.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "invalid frame size: $size" }
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    fun buildSendImageRequest(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): JSONObject =
        JSONObject().apply {
            put("action", ACTION_SEND_IMAGE)
            put("roomId", roomId)
            put("imagePaths", JSONArray(imagePaths))
            if (threadId != null) put("threadId", threadId)
            if (threadScope != null) put("threadScope", threadScope)
        }

    fun buildSuccessResponse(): JSONObject =
        JSONObject().apply {
            put("status", STATUS_SENT)
        }

    fun buildFailureResponse(error: String): JSONObject =
        JSONObject().apply {
            put("status", STATUS_FAILED)
            put("error", error)
        }
}
```

- [ ] **Step 2: Verify app module compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/ImageBridgeProtocol.kt
git commit -m "feat(app): add UDS image bridge protocol codec"
```

---

### Task 3: Protocol Frame Codec Tests

**Files:**
- Create: `app/src/test/java/party/qwer/iris/ImageBridgeProtocolTest.kt`

- [ ] **Step 1: Write protocol tests**

```kotlin
package party.qwer.iris

import org.json.JSONObject
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.DataOutputStream
import java.io.EOFException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ImageBridgeProtocolTest {
    @Test
    fun `writeFrame then readFrame roundtrips`() {
        val original = JSONObject().apply {
            put("action", "send_image")
            put("roomId", 12345L)
        }
        val buffer = ByteArrayOutputStream()
        ImageBridgeProtocol.writeFrame(buffer, original)

        val restored = ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        assertEquals("send_image", restored.getString("action"))
        assertEquals(12345L, restored.getLong("roomId"))
    }

    @Test
    fun `readFrame rejects frame exceeding max size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(ImageBridgeProtocol.MAX_FRAME_SIZE + 1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame rejects zero-length frame`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(0)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame rejects negative frame size`() {
        val buffer = ByteArrayOutputStream()
        DataOutputStream(buffer).writeInt(-1)
        assertFailsWith<IllegalArgumentException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `readFrame throws on truncated payload`() {
        val buffer = ByteArrayOutputStream()
        val dos = DataOutputStream(buffer)
        dos.writeInt(100)
        dos.write(ByteArray(10))
        assertFailsWith<EOFException> {
            ImageBridgeProtocol.readFrame(ByteArrayInputStream(buffer.toByteArray()))
        }
    }

    @Test
    fun `buildSendImageRequest includes all fields`() {
        val request = ImageBridgeProtocol.buildSendImageRequest(
            roomId = 999L,
            imagePaths = listOf("/a.png", "/b.png"),
            threadId = 42L,
            threadScope = 2,
        )
        assertEquals("send_image", request.getString("action"))
        assertEquals(999L, request.getLong("roomId"))
        assertEquals(2, request.getJSONArray("imagePaths").length())
        assertEquals("/a.png", request.getJSONArray("imagePaths").getString(0))
        assertEquals(42L, request.getLong("threadId"))
        assertEquals(2, request.getInt("threadScope"))
    }

    @Test
    fun `buildSendImageRequest omits null threadId and threadScope`() {
        val request = ImageBridgeProtocol.buildSendImageRequest(
            roomId = 1L,
            imagePaths = listOf("/x.png"),
            threadId = null,
            threadScope = null,
        )
        assertTrue(!request.has("threadId"))
        assertTrue(!request.has("threadScope"))
    }

    @Test
    fun `buildSuccessResponse has sent status`() {
        val response = ImageBridgeProtocol.buildSuccessResponse()
        assertEquals("sent", response.getString("status"))
    }

    @Test
    fun `buildFailureResponse carries error message`() {
        val response = ImageBridgeProtocol.buildFailureResponse("room not found")
        assertEquals("failed", response.getString("status"))
        assertEquals("room not found", response.getString("error"))
    }

    @Test
    fun `multiple frames in sequence`() {
        val buffer = ByteArrayOutputStream()
        val msg1 = JSONObject().apply { put("seq", 1) }
        val msg2 = JSONObject().apply { put("seq", 2) }
        ImageBridgeProtocol.writeFrame(buffer, msg1)
        ImageBridgeProtocol.writeFrame(buffer, msg2)

        val input = ByteArrayInputStream(buffer.toByteArray())
        assertEquals(1, ImageBridgeProtocol.readFrame(input).getInt("seq"))
        assertEquals(2, ImageBridgeProtocol.readFrame(input).getInt("seq"))
    }
}
```

- [ ] **Step 2: Run tests**

```bash
./gradlew :app:testDebugUnitTest --tests "party.qwer.iris.ImageBridgeProtocolTest"
```

Expected: all tests PASS

- [ ] **Step 3: Commit**

```bash
git add app/src/test/java/party/qwer/iris/ImageBridgeProtocolTest.kt
git commit -m "test(app): add UDS image bridge protocol codec tests"
```

---

### Task 4: UDS Client

**Files:**
- Create: `app/src/main/java/party/qwer/iris/UdsImageBridgeClient.kt`

- [ ] **Step 1: Create UdsImageBridgeClient.kt**

```kotlin
package party.qwer.iris

import android.net.LocalSocket
import android.net.LocalSocketAddress
import org.json.JSONObject
import java.io.IOException

internal data class ImageBridgeResult(
    val success: Boolean,
    val error: String? = null,
)

internal class UdsImageBridgeClient(
    private val socketName: String = ImageBridgeProtocol.SOCKET_NAME,
    private val connectTimeoutMs: Int = 5_000,
    private val readTimeoutMs: Int = 30_000,
) {
    fun sendImage(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ): ImageBridgeResult {
        val request = ImageBridgeProtocol.buildSendImageRequest(
            roomId, imagePaths, threadId, threadScope,
        )
        val socket = LocalSocket()
        try {
            socket.connect(
                LocalSocketAddress(socketName, LocalSocketAddress.Namespace.ABSTRACT),
            )
            socket.soTimeout = readTimeoutMs

            ImageBridgeProtocol.writeFrame(socket.outputStream, request)
            socket.shutdownOutput()

            val response = ImageBridgeProtocol.readFrame(socket.inputStream)
            return parseResponse(response)
        } catch (e: IOException) {
            return ImageBridgeResult(
                success = false,
                error = "bridge connection failed: ${e.message}",
            )
        } finally {
            runCatching { socket.close() }
        }
    }

    private fun parseResponse(response: JSONObject): ImageBridgeResult {
        val status = response.optString("status", "")
        return if (status == ImageBridgeProtocol.STATUS_SENT) {
            ImageBridgeResult(success = true)
        } else {
            ImageBridgeResult(
                success = false,
                error = response.optString("error", "unknown bridge error"),
            )
        }
    }
}
```

- [ ] **Step 2: Verify app module compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/UdsImageBridgeClient.kt
git commit -m "feat(app): add UDS image bridge client"
```

---

### Task 5: UdsImageReplySender

**Files:**
- Create: `app/src/main/java/party/qwer/iris/UdsImageReplySender.kt`

- [ ] **Step 1: Create UdsImageReplySender.kt**

This implements the existing `NativeImageReplySender` interface (defined at `KakaoNativeImageReplySender.kt:20-27`):

```kotlin
package party.qwer.iris

import android.net.Uri

internal class UdsImageReplySender(
    private val client: UdsImageBridgeClient = UdsImageBridgeClient(),
) : NativeImageReplySender {
    override fun send(
        roomId: Long,
        uris: List<Uri>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val paths = uris.map { uri ->
            uri.path ?: error("image URI has no file path: $uri")
        }
        IrisLogger.info(
            "[UdsImageReplySender] sending ${paths.size} image(s) to bridge" +
                " room=$roomId threadId=$threadId scope=$threadScope",
        )
        val result = client.sendImage(roomId, paths, threadId, threadScope)
        if (!result.success) {
            error("image bridge send failed: ${result.error}")
        }
        IrisLogger.info("[UdsImageReplySender] bridge send completed room=$roomId")
    }
}
```

- [ ] **Step 2: Verify app module compiles**

```bash
./gradlew :app:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/UdsImageReplySender.kt
git commit -m "feat(app): add UdsImageReplySender implementing NativeImageReplySender"
```

---

### Task 6: Wire UdsImageReplySender in Main.kt

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/Main.kt:24`

- [ ] **Step 1: Replace default sender wiring**

In `Main.kt`, line 24 currently reads:

```kotlin
val replyService = ReplyService(configManager)
```

Change to:

```kotlin
val replyService = ReplyService(configManager, UdsImageReplySender())
```

This passes the UDS-backed sender instead of letting `ReplyService` use the default `KakaoNativeImageReplySender()`.

- [ ] **Step 2: Verify app builds and existing tests pass**

```bash
./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/Main.kt
git commit -m "feat(app): wire UdsImageReplySender as default image sender"
```

---

### Task 7: Bridge Xposed Entry Point

**Files:**
- Create: `bridge/src/main/java/party/qwer/iris/bridge/IrisBridgeModule.kt`

- [ ] **Step 1: Create IrisBridgeModule.kt**

```kotlin
package party.qwer.iris.bridge

import android.app.Application
import android.util.Log
import de.robv.android.xposed.IXposedHookLoadPackage
import de.robv.android.xposed.XC_MethodHook
import de.robv.android.xposed.XposedHelpers
import de.robv.android.xposed.callbacks.XC_LoadPackage

class IrisBridgeModule : IXposedHookLoadPackage {
    companion object {
        private const val TAG = "IrisBridge"
        private const val TARGET_PACKAGE = "com.kakao.talk"
    }

    override fun handleLoadPackage(lpparam: XC_LoadPackage.LoadPackageParam) {
        if (lpparam.packageName != TARGET_PACKAGE) return
        Log.i(TAG, "loaded into $TARGET_PACKAGE, hooking Application.onCreate")

        XposedHelpers.findAndHookMethod(
            Application::class.java,
            "onCreate",
            object : XC_MethodHook() {
                override fun afterHookedMethod(param: MethodHookParam) {
                    val app = param.thisObject as Application
                    Log.i(TAG, "Application.onCreate — starting image bridge server")
                    ImageBridgeServer.start(app, lpparam.classLoader)
                }
            },
        )
    }
}
```

- [ ] **Step 2: Verify bridge builds**

```bash
./gradlew :bridge:assembleDebug
```

Expected: BUILD SUCCESSFUL

- [ ] **Step 3: Commit**

```bash
git add bridge/src/main/java/party/qwer/iris/bridge/IrisBridgeModule.kt
git commit -m "feat(bridge): add Xposed entry point hooking Application.onCreate"
```

---

### Task 8: Bridge UDS Server

**Files:**
- Create: `bridge/src/main/java/party/qwer/iris/bridge/ImageBridgeServer.kt`

- [ ] **Step 1: Create ImageBridgeServer.kt**

The server uses the same frame protocol as the Iris client (4-byte length prefix + JSON).

```kotlin
package party.qwer.iris.bridge

import android.content.Context
import android.net.LocalServerSocket
import android.net.LocalSocket
import android.util.Log
import org.json.JSONObject
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.InputStream
import java.io.OutputStream
import java.util.concurrent.atomic.AtomicBoolean

internal object ImageBridgeServer {
    private const val TAG = "IrisBridge"
    private const val SOCKET_NAME = "iris-image-bridge"
    private const val MAX_FRAME_SIZE = 1_048_576

    private val running = AtomicBoolean(false)
    @Volatile
    private var sender: KakaoImageSender? = null

    fun start(context: Context, classLoader: ClassLoader) {
        if (!running.compareAndSet(false, true)) {
            Log.w(TAG, "bridge server already running")
            return
        }
        sender = KakaoImageSender(context, classLoader)
        Thread({
            try {
                serve()
            } catch (e: Exception) {
                Log.e(TAG, "bridge server crashed", e)
                running.set(false)
            }
        }, "iris-bridge-server").apply {
            isDaemon = true
            start()
        }
    }

    private fun serve() {
        val serverSocket = LocalServerSocket(SOCKET_NAME)
        Log.i(TAG, "bridge server listening on @$SOCKET_NAME")
        while (running.get()) {
            val client = serverSocket.accept()
            handleClient(client)
        }
    }

    private fun handleClient(client: LocalSocket) {
        try {
            val request = readFrame(client.inputStream)
            val action = request.optString("action", "")
            val response = when (action) {
                "send_image" -> handleSendImage(request)
                else -> failureResponse("unknown action: $action")
            }
            writeFrame(client.outputStream, response)
        } catch (e: Exception) {
            Log.e(TAG, "client handler error", e)
            runCatching {
                writeFrame(client.outputStream, failureResponse(e.message ?: "internal error"))
            }
        } finally {
            runCatching { client.close() }
        }
    }

    private fun handleSendImage(request: JSONObject): JSONObject {
        val roomId = request.getLong("roomId")
        val pathsArray = request.getJSONArray("imagePaths")
        val paths = (0 until pathsArray.length()).map { pathsArray.getString(it) }
        val threadId = if (request.has("threadId")) request.getLong("threadId") else null
        val threadScope = if (request.has("threadScope")) request.getInt("threadScope") else null

        val imageSender = sender ?: return failureResponse("sender not initialized")
        return try {
            imageSender.send(roomId, paths, threadId, threadScope)
            JSONObject().apply { put("status", "sent") }
        } catch (e: Exception) {
            Log.e(TAG, "send failed room=$roomId", e)
            failureResponse(e.message ?: "send failed")
        }
    }

    private fun writeFrame(output: OutputStream, json: JSONObject) {
        val bytes = json.toString().toByteArray(Charsets.UTF_8)
        DataOutputStream(output).apply {
            writeInt(bytes.size)
            write(bytes)
            flush()
        }
    }

    private fun readFrame(input: InputStream): JSONObject {
        val dis = DataInputStream(input)
        val size = dis.readInt()
        require(size in 1..MAX_FRAME_SIZE) { "invalid frame size: $size" }
        val bytes = ByteArray(size)
        dis.readFully(bytes)
        return JSONObject(String(bytes, Charsets.UTF_8))
    }

    private fun failureResponse(error: String) =
        JSONObject().apply {
            put("status", "failed")
            put("error", error)
        }
}
```

- [ ] **Step 2: Verify bridge builds**

```bash
./gradlew :bridge:assembleDebug
```

Expected: BUILD SUCCESSFUL (will have a compile error until Task 9 creates `KakaoImageSender`)

- [ ] **Step 3: Commit** (after Task 9)

---

### Task 9: Kakao In-Process Image Sender

**Files:**
- Create: `bridge/src/main/java/party/qwer/iris/bridge/KakaoImageSender.kt`

This is the core sender that runs **inside `com.kakao.talk`**. It ports the reflection-based sending logic from `KakaoNativeImageReplySender.kt` but skips the bootstrap ceremony (no `resolveActivityThread`, `createPackageContext`, `seedHardwareContext`) since we're already in Kakao's process with the real classloader and context.

Key difference from shell-native: `ChatMediaSender.p()` will succeed because the caller identity is `com.kakao.talk`, resolving the SecurityException that blocked the shell-native approach.

- [ ] **Step 1: Create KakaoImageSender.kt**

```kotlin
package party.qwer.iris.bridge

import android.content.Context
import android.net.Uri
import android.util.Log
import org.json.JSONObject
import java.io.File
import java.lang.reflect.Modifier
import java.lang.reflect.Proxy

internal class KakaoImageSender(
    private val context: Context,
    private val loader: ClassLoader,
) {
    companion object {
        private const val TAG = "IrisBridge"
    }

    fun send(
        roomId: Long,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        require(imagePaths.isNotEmpty()) { "no image paths" }
        Log.i(TAG, "send start room=$roomId images=${imagePaths.size} threadId=$threadId scope=$threadScope")

        val chatRoom = resolveChatRoom(roomId) ?: error("chat room not found: $roomId")

        if (imagePaths.size == 1) {
            sendSingleImage(chatRoom, imagePaths.first(), threadId, threadScope)
        } else {
            sendMultipleImages(chatRoom, imagePaths, threadId, threadScope)
        }
        Log.i(TAG, "send completed room=$roomId")
    }

    private fun sendSingleImage(
        chatRoom: Any,
        imagePath: String,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val mediaSenderClass = loadClass("bh.c")
        val mediaItemClass = loadClass("com.kakao.talk.model.media.MediaItem")
        val function0Class = loadClass("kotlin.jvm.functions.Function0")
        val function1Class = loadClass("kotlin.jvm.functions.Function1")
        val sendWithChatRoomInThread = threadId != null && threadScope == 3

        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisBridgeSendInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0) as? JSONObject
                    "toString" -> "IrisBridgeAttachmentDecorator"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }

        val sender =
            mediaSenderClass
                .getConstructor(
                    chatRoom.javaClass,
                    java.lang.Long::class.java,
                    function0Class,
                    function1Class,
                ).newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)

        val mediaItem =
            mediaItemClass
                .getConstructor(String::class.java, Long::class.javaPrimitiveType)
                .newInstance(imagePath, 0L)

        Log.i(TAG, "invoking ChatMediaSender.n path=$imagePath")
        mediaSenderClass
            .getMethod("n", mediaItemClass, Boolean::class.javaPrimitiveType)
            .invoke(sender, mediaItem, false)
        Log.i(TAG, "ChatMediaSender.n returned")
    }

    private fun sendMultipleImages(
        chatRoom: Any,
        imagePaths: List<String>,
        threadId: Long?,
        threadScope: Int?,
    ) {
        val mediaSenderClass = loadClass("bh.c")
        val typeClass = loadChatMessageTypeClass()
        val writeTypeClass = loadClass("com.kakao.talk.manager.send.ChatSendingLogRequest\$c")
        val listenerClass = loadClass("com.kakao.talk.manager.send.m")
        val function0Class = loadClass("kotlin.jvm.functions.Function0")
        val function1Class = loadClass("kotlin.jvm.functions.Function1")
        val sendWithChatRoomInThread = threadId != null && threadScope == 3

        val sendWithThreadProxy =
            Proxy.newProxyInstance(loader, arrayOf(function0Class)) { _, method, _ ->
                when (method.name) {
                    "invoke" -> sendWithChatRoomInThread
                    "toString" -> "IrisBridgeSendInThread($sendWithChatRoomInThread)"
                    "hashCode" -> sendWithChatRoomInThread.hashCode()
                    "equals" -> false
                    else -> null
                }
            }
        val attachmentDecoratorProxy =
            Proxy.newProxyInstance(loader, arrayOf(function1Class)) { _, method, args ->
                when (method.name) {
                    "invoke" -> args?.getOrNull(0) as? JSONObject
                    "toString" -> "IrisBridgeAttachmentDecorator"
                    "hashCode" -> 0
                    "equals" -> false
                    else -> null
                }
            }

        val sender =
            mediaSenderClass
                .getConstructor(
                    chatRoom.javaClass,
                    java.lang.Long::class.java,
                    function0Class,
                    function1Class,
                ).newInstance(chatRoom, threadId, sendWithThreadProxy, attachmentDecoratorProxy)

        val uris = ArrayList<Uri>(imagePaths.size)
        imagePaths.forEach { path -> uris.add(Uri.fromFile(File(path))) }

        val type = enumConstant(typeClass, if (imagePaths.size == 1) "Photo" else "MultiPhoto")
        val writeTypeNone = enumConstant(writeTypeClass, "None")
        val sendMethod =
            mediaSenderClass.getMethod(
                "p",
                List::class.java,
                typeClass,
                String::class.java,
                JSONObject::class.java,
                JSONObject::class.java,
                writeTypeClass,
                Boolean::class.javaPrimitiveType,
                Boolean::class.javaPrimitiveType,
                listenerClass,
            )
        Log.i(TAG, "invoking ChatMediaSender.p uriCount=${uris.size}")
        sendMethod.invoke(sender, uris, type, null, null, null, writeTypeNone, false, false, null)
        Log.i(TAG, "ChatMediaSender.p returned")
    }

    private fun resolveChatRoom(roomId: Long): Any? {
        // Primary path: MasterDatabase → roomDao.h(roomId) → hp.t conversion
        runCatching {
            val masterDatabaseClass = loadClass("com.kakao.talk.database.MasterDatabase")
            val instanceField =
                masterDatabaseClass.declaredFields.firstOrNull {
                    Modifier.isStatic(it.modifiers) && it.type == masterDatabaseClass
                }?.apply { isAccessible = true }
                    ?: error("MasterDatabase instance field not found")
            val db = instanceField.get(null) ?: error("MasterDatabase not initialized")
            val roomDao = db.javaClass.getMethod("O").invoke(db)
            val entity = roomDao.javaClass
                .getMethod("h", Long::class.javaPrimitiveType)
                .invoke(roomDao, roomId) ?: return null
            val chatRoomClass = loadClass("hp.t")
            val companion =
                chatRoomClass.declaredFields
                    .asSequence()
                    .filter { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }.firstOrNull { candidate ->
                        candidate.javaClass.methods.any { method ->
                            method.name == "c" &&
                                method.parameterCount == 1 &&
                                chatRoomClass.isAssignableFrom(method.returnType) &&
                                method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                        }
                    }
            if (companion != null) {
                val resolver =
                    companion.javaClass.methods.first { method ->
                        method.name == "c" &&
                            method.parameterCount == 1 &&
                            chatRoomClass.isAssignableFrom(method.returnType) &&
                            method.parameterTypes[0].isAssignableFrom(entity.javaClass)
                    }
                return resolver.invoke(companion, entity)
            }
            val ctor =
                chatRoomClass.declaredConstructors.firstOrNull { constructor ->
                    constructor.parameterTypes.size == 1 &&
                        constructor.parameterTypes[0].isAssignableFrom(entity.javaClass)
                } ?: error("hp.t companion/constructor resolver not found")
            ctor.isAccessible = true
            return ctor.newInstance(entity)
        }.onFailure {
            Log.e(TAG, "primary room resolver failed: ${it.message}", it)
        }

        // Fallback: hp.J0 singleton manager
        runCatching {
            val managerClass = loadClass("hp.J0")
            val companion =
                managerClass.declaredFields
                    .asSequence()
                    .filter { Modifier.isStatic(it.modifiers) }
                    .mapNotNull { field ->
                        runCatching {
                            field.isAccessible = true
                            field.get(null)
                        }.getOrNull()
                    }.firstOrNull { candidate ->
                        candidate.javaClass.methods.any { method ->
                            method.name == "j" &&
                                method.parameterCount == 0 &&
                                method.returnType == managerClass
                        }
                    }
            val manager =
                companion?.javaClass?.methods?.firstOrNull { method ->
                    method.name == "j" &&
                        method.parameterCount == 0 &&
                        method.returnType == managerClass
                }?.invoke(companion)
                    ?: managerClass.methods.firstOrNull { method ->
                        Modifier.isStatic(method.modifiers) &&
                            method.parameterCount == 0 &&
                            method.returnType == managerClass
                    }?.invoke(null)
                    ?: error("hp.J0 singleton accessor not found")
            val broadResolver =
                manager.javaClass.getMethod(
                    "e0",
                    Long::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                    Boolean::class.javaPrimitiveType,
                )
            val chatRoom = broadResolver.invoke(manager, roomId, true, true)
            if (chatRoom != null) return chatRoom
            val resolver = manager.javaClass.getMethod("d0", Long::class.javaPrimitiveType)
            return resolver.invoke(manager, roomId)
        }.onFailure {
            Log.e(TAG, "hp.J0 resolver failed: ${it.message}", it)
        }

        return null
    }

    @Suppress("UNCHECKED_CAST")
    private fun enumConstant(enumClass: Class<*>, name: String): Any =
        enumClass.enumConstants?.firstOrNull { (it as Enum<*>).name == name }
            ?: error("enum constant $name not found in ${enumClass.name}")

    private fun loadClass(className: String): Class<*> =
        Class.forName(className, true, loader)

    private fun loadChatMessageTypeClass(): Class<*> =
        runCatching { loadClass("Op.EnumC16810c") }.getOrElse {
            loadClass("Op.c")
        }
}
```

- [ ] **Step 2: Verify bridge builds**

```bash
./gradlew :bridge:assembleDebug
```

Expected: BUILD SUCCESSFUL, output: `output/IrisBridge-debug.apk`

- [ ] **Step 3: Commit bridge server + sender together**

```bash
git add bridge/src/main/java/party/qwer/iris/bridge/
git commit -m "feat(bridge): add UDS server and Kakao in-process image sender"
```

---

### Task 10: Quarantine Old Shell-Native Code

**Files:**
- Modify: `app/src/main/java/party/qwer/iris/KakaoNativeImageReplySender.kt`

The `NativeImageReplySender` interface and `KakaoNativeImageReplySender` class remain in place — they are still the fallback if the bridge is unavailable. The `ReflectionKakaoNativeRuntime` object with its unstable `IZ.V` experiment is the code to quarantine.

- [ ] **Step 1: Add deprecation annotation to ReflectionKakaoNativeRuntime**

At `KakaoNativeImageReplySender.kt:51`, add `@Deprecated` to mark the shell-native runtime as superseded:

```kotlin
@Deprecated("Superseded by LSPosed bridge module — caller identity requires in-process execution")
internal object ReflectionKakaoNativeRuntime : KakaoNativeRuntime {
```

- [ ] **Step 2: Verify build and tests pass**

```bash
./gradlew :app:assembleDebug && ./gradlew :app:testDebugUnitTest
```

Expected: BUILD SUCCESSFUL, all tests pass (deprecation warnings are allowed)

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/party/qwer/iris/KakaoNativeImageReplySender.kt
git commit -m "chore(app): deprecate ReflectionKakaoNativeRuntime — superseded by bridge"
```

---

### Task 11: End-to-End Integration Verification

**Files:** none (device testing)

- [ ] **Step 1: Build both APKs**

```bash
./gradlew assembleDebug
```

Expected: `output/Iris-debug.apk` and `output/IrisBridge-debug.apk`

- [ ] **Step 2: Deploy and activate bridge module**

```bash
# Push bridge APK to device
adb -s emulator-5554 install -r output/IrisBridge-debug.apk

# In LSPosed Manager: enable IrisBridge module, scope to com.kakao.talk
# Reboot or force-stop com.kakao.talk to apply hooks
```

- [ ] **Step 3: Deploy Iris and verify bridge connection**

```bash
# Deploy Iris using existing workflow
ssh -i /home/kapu/gemini/hololive-bot/KR.key root@100.100.1.4 \
  'cd /root/work/arm-iris-runtime && \
   IRIS_PROJECT_DIR=/root/work/Iris IRIS_DEVICE=emulator-5554 IRIS_FORCE_RESTART=1 \
   ./scripts/iris-init.sh'
```

- [ ] **Step 4: Send test image**

```bash
# Use the fixed test identifiers from the handoff
curl -X POST http://100.100.1.4:PORT/reply-image \
  -H "Content-Type: application/json" \
  -H "Authorization: Bearer $BOT_TOKEN" \
  -d '{
    "room": "18478615493603057",
    "type": "image",
    "threadId": "3804936595914842113",
    "threadScope": 2,
    "text": "<base64 1x1 PNG>"
  }'
```

Expected: `202 Accepted`, and the image appears in the Kakao thread.

- [ ] **Step 5: Verify via DB**

```bash
adb -s emulator-5554 shell sqlite3 /data/data/com.kakao.talk/databases/KakaoTalk.db \
  'select id,chat_id,type,thread_id,scope,substr(attachment,1,240) from chat_logs where chat_id=18478615493603057 order by id desc limit 5;'
```

Expected: new row with correct `thread_id`, `scope`, and non-empty `attachment`

- [ ] **Step 6: Check logcat for bridge activity**

```bash
adb -s emulator-5554 logcat -s IrisBridge:* | head -30
```

Expected: logs showing "bridge server listening", "send start", "ChatMediaSender.n returned" or "ChatMediaSender.p returned"

---

## Notes

### Why not a shared protocol module?

The protocol is ~50 lines of code and changes infrequently. Duplicating it in both modules avoids adding a `:protocol` module, which adds Gradle sync time and coordination overhead for minimal benefit. YAGNI.

### Obfuscated class names

The Kakao internal class names (`bh.c`, `hp.t`, `hp.J0`, `Op.c`, etc.) are version-specific due to ProGuard. When Kakao updates, these names may change. The bridge sender should be updated accordingly — the structure of the API calls remains stable, only the class/method names shift.

### Connection-per-request vs persistent connection

V1 uses connection-per-request for simplicity. Image sends are infrequent (relative to text), so the overhead is negligible. If performance becomes an issue, the client can maintain a persistent connection with reconnection logic.

### Xposed API JAR

The `api-82.jar` is compile-only and not committed to the repository. Each developer must obtain it from Xposed/LSPosed releases. The bridge module compiles without it failing at runtime since LSPosed provides the classes.
