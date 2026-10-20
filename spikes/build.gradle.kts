plugins {
    alias(libs.plugins.android.application)
}

// Phase 0 spike app. Not shipped. A separate applicationId so it can sit
// beside the real app on a test device.
android {
    namespace = "dev.zacx.muster.spikes"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zacx.muster.spikes"
        minSdk = 30
        targetSdk = 36
        versionCode = 1
        versionName = "phase0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.biometric)
    implementation(libs.androidx.webkit)
}
