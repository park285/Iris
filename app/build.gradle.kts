import org.gradle.api.tasks.Copy

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.detekt)
    alias(libs.plugins.ktlint)
}

android {
    namespace = "party.qwer.iris"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            multiDexEnabled = false
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
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
                )
        }
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
            rename { "Iris-$variantName.apk" }
        }
    tasks.matching { it.name == assembleTaskName }.configureEach {
        finalizedBy(copyTask)
    }
}

registerAssembleOutputCopyTask("debug")
registerAssembleOutputCopyTask("release")

// Detekt 설정
detekt {
    buildUponDefaultConfig = true
    allRules = false
    config.setFrom(files("${rootProject.rootDir}/config/detekt/detekt.yml"))
    baseline = file("${rootProject.rootDir}/config/detekt/baseline.xml")
}

tasks.withType<io.gitlab.arturbosch.detekt.Detekt>().configureEach {
    jvmTarget = "21"
}

tasks.withType<io.gitlab.arturbosch.detekt.DetektCreateBaselineTask>().configureEach {
    jvmTarget = "21"
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
    implementation(libs.kotlinx.serialization.json)

    implementation(libs.ktor.server.cio)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.status.pages)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.kotlinx.json)
    implementation(libs.okhttp)
    implementation(libs.slf4j.nop)

    testImplementation(kotlin("test-junit"))
}
