# ── Iris App R8 Rules ──────────────────────────────────────────────────────

# 디버그 스택트레이스 보존
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Entry point (app_process) ─────────────────────────────────────────────
-keep class party.qwer.iris.Main {
    public static void main(java.lang.String[]);
}

# ── kotlinx.serialization ─────────────────────────────────────────────────
# 운영 규칙: @Serializable DTO는 party.qwer.iris.model 패키지에만 둘 것.
# 이 범위 밖에 추가하면 R8이 serializer를 제거하여 release에서 크래시 발생.
# ProguardSerializableGuardTest가 이를 검증함.
-keepattributes *Annotation*, InnerClasses
-dontnote kotlinx.serialization.**

-keep,includedescriptorclasses class party.qwer.iris.model.**$$serializer { *; }
-keepclassmembers class party.qwer.iris.model.** {
    *** Companion;
    *** INSTANCE;
    kotlinx.serialization.KSerializer serializer(...);
}
-keepclassmembers enum party.qwer.iris.model.** {
    **[] values();
    ** valueOf(java.lang.String);
}

# @SerialName 어노테이션이 제거되면 JSON 역직렬화 실패
-keep @kotlinx.serialization.Serializable class party.qwer.iris.model.** {
    *;
}

# 커스텀 KSerializer
-keep class party.qwer.iris.util.IntAsStringSerializer { *; }
-keep class party.qwer.iris.util.StrictLongSerializer { *; }
-keep class party.qwer.iris.util.StrictIntSerializer { *; }

# ── Ktor (ServiceLoader 기반 엔진 검색) ───────────────────────────────────
-keep class io.ktor.server.netty.EngineMain { *; }
-keep class io.ktor.server.netty.NettyApplicationEngine { *; }
-keepnames class io.ktor.server.engine.** { *; }
-keepnames class io.ktor.server.application.** { *; }

# Ktor content negotiation + serialization 플러그인
-keep class io.ktor.serialization.kotlinx.json.** { *; }
-keep class io.ktor.server.plugins.contentnegotiation.** { *; }

# ── Netty ──────────────────────────────────────────────────────────────────
-dontwarn io.netty.**
-keep class io.netty.channel.** { *; }
-keep class io.netty.handler.** { *; }
-keep class io.netty.buffer.** { *; }
-keep class io.netty.util.** { *; }

# ── OkHttp ─────────────────────────────────────────────────────────────────
-dontwarn okhttp3.internal.platform.**
-dontwarn org.bouncycastle.**
-dontwarn org.conscrypt.**
-dontwarn org.openjsse.**

# ── SLF4J (NOP 바인딩) ─────────────────────────────────────────────────────
-dontwarn org.slf4j.**

# ── Optional ServiceLoader entries bundled by transitive libs ─────────────
-dontwarn reactor.blockhound.integration.BlockHoundIntegration

# ── JVM management API (Android에 없음, Ktor 디버그 감지용) ─────────────────
-dontwarn java.lang.management.ManagementFactory
-dontwarn java.lang.management.RuntimeMXBean

# ── Kotlin ─────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }

# ── Android hidden API reflection callers ──────────────────────────────────
# 이 클래스들은 시스템 클래스를 문자열로 참조하므로 메서드 시그니처 보존 필요 없음
# R8이 call graph를 추적하므로 내부 호출은 자동 유지됨

# ── ImageBridge protocol (공유 모듈) ───────────────────────────────────────
-keep class party.qwer.iris.ImageBridgeProtocol { *; }
-keep class party.qwer.iris.ImageBridgeProtocol$* { *; }
