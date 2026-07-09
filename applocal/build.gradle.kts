// SardineTracker local mode: the whole tracker runs inside this app.
// Chaquopy embeds CPython; Flask serves on 127.0.0.1 and the WebView is the UI.
// Kept as a separate module from :app so the companion APK never carries the
// Python runtime (see notes/local-mode-plan.md).
plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("com.chaquo.python")
}

android {
    namespace = "com.sardinetracker.local"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.sardinetracker.sync.local"
        minSdk = 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1-milestone1"

        ndk {
            // arm64 = real phones; x86_64 = the emulator. Chaquopy requires
            // an explicit list, and each ABI adds a full Python runtime.
            abiFilters += listOf("arm64-v8a", "x86_64")
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
        // 3.13, not Fedora's 3.14: Chaquopy's wheel repo doesn't carry native
        // wheels (MarkupSafe today; numpy/pandas/scipy at milestone 2) for
        // 3.14 yet. Build machine provides 3.13 via `uv python install 3.13`.
        version = "3.13"
        // No buildPython override: Chaquopy finds `python3.13` on PATH
        // (~/.local/bin shim from uv locally; setup-python or similar in CI).
        pip {
            install("flask")
        }
    }
}

dependencies {
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
