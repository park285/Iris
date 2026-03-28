# ── Iris Bridge (Xposed Module) R8 Rules ──────────────────────────────────

# 디버그 스택트레이스 보존
-keepattributes SourceFile,LineNumberTable
-renamesourcefileattribute SourceFile

# ── Xposed 진입점 (xposed_init에 명시) ────────────────────────────────────
-keep class party.qwer.iris.imagebridge.runtime.IrisBridgeModule { *; }

# ── Xposed API (compileOnly — 런타임은 프레임워크 제공) ────────────────────
-dontwarn de.robv.android.xposed.**
-keep class de.robv.android.xposed.** { *; }

# ── ImageBridge protocol (공유 모듈) ───────────────────────────────────────
-keep class party.qwer.iris.ImageBridgeProtocol { *; }
-keep class party.qwer.iris.ImageBridgeProtocol$* { *; }

# ── Kotlin ─────────────────────────────────────────────────────────────────
-keep class kotlin.Metadata { *; }
