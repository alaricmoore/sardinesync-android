// SardineTracker local mode: the whole tracker runs inside this app.
// Chaquopy embeds CPython; Flask serves on 127.0.0.1 and the WebView is the UI.
// Kept as a separate module from :app so the companion APK never carries the
// Python runtime (see notes/local-mode-plan.md).
import java.io.FileInputStream
import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

// Release signing secrets live in applocal/keystore.properties (gitignored).
// Absent on a fresh clone — the release build then stays unsigned rather than
// failing. Same shape and same keystore as the :app module.
val keystorePropsFile = file("keystore.properties")
val keystoreProps = Properties().apply {
    if (keystorePropsFile.exists()) load(FileInputStream(keystorePropsFile))
}

android {
    namespace = "com.sardinetracker.local"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sardinetracker.sync.local"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"

        ndk {
            // arm64 = real phones; x86_64 = the emulator. Chaquopy requires
            // an explicit list, and each ABI adds a full Python runtime.
            abiFilters += listOf("arm64-v8a", "x86_64")
        }
    }

    signingConfigs {
        create("release") {
            if (keystorePropsFile.exists()) {
                storeFile = file(keystoreProps["storeFile"] as String)
                storePassword = keystoreProps["storePassword"] as String
                keyAlias = keystoreProps["keyAlias"] as String
                keyPassword = keystoreProps["keyPassword"] as String
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            if (keystorePropsFile.exists()) {
                signingConfig = signingConfigs.getByName("release")
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

chaquopy {
    defaultConfig {
        // 3.10, not newer: the only Python with the FULL native-wheel set in
        // Chaquopy's repo (scipy stops at cp310; numpy/bcrypt reach cp313).
        // scipy powers the lag-correlation and forecast-accuracy pages, so it
        // wins. Build machine provides `python3.10` on PATH via
        // `uv python install 3.10` (or setup-python in CI).
        version = "3.10"
        pip {
            // Web-app dependencies only — analysis-script extras like pandas
            // are NOT used by app.py and stay out of the APK.
            install("flask")
            install("flask-login")
            install("flask-wtf")
            install("bcrypt")
            install("apscheduler")   // imported at module top; never started (SARDINE_EMBEDDED)
            install("requests")
            install("numpy")
            install("scipy")
            install("pypdf")
        }
    }
}

dependencies {
    implementation(project(":core"))
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.8.7")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
