plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
}

android {
    namespace = "party.qwer.iris.kakaothreadfix"
    compileSdk = 35

    defaultConfig {
        applicationId = "party.qwer.iris.kakaothreadfix"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
        }
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_21
        targetCompatibility = JavaVersion.VERSION_21
    }

    packaging {
        resources {
            excludes += "META-INF/*"
        }
    }
}

kotlin {
    jvmToolchain(21)
}

dependencies {
    compileOnly("de.robv.android.xposed:api:82")
}

