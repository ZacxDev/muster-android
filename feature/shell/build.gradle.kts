plugins {
    alias(libs.plugins.android.library)
}

android {
    namespace = "dev.zacx.muster.feature.shell"
    compileSdk = 37
    defaultConfig { minSdk = 30 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:net"))
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.webkit)
    api(libs.androidx.swiperefreshlayout)
    api(libs.androidx.activity)
    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    // Runs the injected pull-to-refresh script for real (PullProbeScriptJsTest): ES5 on the JVM, no browser.
    testImplementation(libs.rhino)
}
