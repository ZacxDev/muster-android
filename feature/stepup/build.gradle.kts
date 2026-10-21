plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.zacx.muster.feature.stepup"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:auth"))
    implementation(libs.androidx.biometric)
}
