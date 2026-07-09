// Shared Health Connect sync machinery used by both apps: :app (companion,
// syncs to a remote server) and :applocal (local mode, syncs to the embedded
// server on 127.0.0.1). Kotlin package stays com.sardinetracker.sync — these
// classes moved here from :app and keeping the package avoids churn (and keeps
// the FQCN of SyncWorker subclasses stable for WorkManager's persisted jobs).
plugins {
    id("com.android.library")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.sardinetracker.core"
    compileSdk = 36

    defaultConfig {
        minSdk = 30
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // api, not implementation: both apps subclass BaseSyncWorker and touch
    // HealthConnectClient types directly.
    api("androidx.health.connect:connect-client:1.1.0")
    api("androidx.work:work-runtime-ktx:2.9.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.8.1")
}
