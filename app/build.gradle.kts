import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)

    alias(libs.plugins.ktlint)
}

android {
    namespace = "party.qwer.iris"
    compileSdk = 35

    defaultConfig {
        minSdk = 33
        targetSdk = 35
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            multiDexEnabled = false
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    lint {
        abortOnError = true
        warningsAsErrors = true
        // 설계상 의도된 패턴
        disable += setOf("PrivateApi", "DiscouragedPrivateApi", "SdCardPath")
        // 버전 업데이트 알림 비활성화
        disable += setOf("GradleDependency", "NewerVersionAvailable", "AndroidGradlePluginVersion", "OldTargetApi")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }
    packaging {
        resources {
            excludes +=
                setOf(
                    "META-INF/INDEX.LIST",
                    "META-INF/io.netty.versions.properties",
                    "META-INF/LICENSE*",
                    "META-INF/NOTICE*",
                    "META-INF/*.md",
                    "META-INF/*.version",
                    "META-INF/DEPENDENCIES",
                    "META-INF/services/reactor.blockhound.integration.BlockHoundIntegration",
                )
        }
    }
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        allWarningsAsErrors.set(true)
    }
}

private fun registerAssembleOutputCopyTask(variantName: String) {
    val assembleTaskName = "assemble${variantName.replaceFirstChar { it.uppercase() }}"
    val copyTaskName = "sync${variantName.replaceFirstChar { it.uppercase() }}ApkToOutput"
    val copyTask =
        tasks.register<Copy>(copyTaskName) {
            from(layout.buildDirectory.dir("outputs/apk/$variantName"))
            include("*.apk")
            into(rootProject.layout.projectDirectory.dir("output"))
            rename { "Iris-$variantName.apk" }
        }
    tasks.matching { it.name == assembleTaskName }.configureEach {
        finalizedBy(copyTask)
    }
}

registerAssembleOutputCopyTask("debug")
registerAssembleOutputCopyTask("release")

// release 빌드 시에만 Android Lint + ktlint 강제 실행
tasks.matching { it.name == "assembleRelease" }.configureEach {
    dependsOn("ktlintCheck")
    dependsOn("lint")
}

// ktlint 설정
ktlint {
    android.set(true)
    outputToConsole.set(true)
    ignoreFailures.set(false)
    filter {
        exclude("**/generated/**")
    }
}

dependencies {
    implementation(project(":imagebridge-protocol"))
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.slf4j.nop)

    testImplementation(kotlin("test-junit"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.org.json)
    testImplementation(libs.ktor.server.test.host)
    testImplementation(libs.robolectric)
    testImplementation(libs.sqlite.jdbc)
}
