plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    // Permanent: fixed at first install and at developer-verification
    // registration. Never change it.
    namespace = "dev.zacx.muster"
    compileSdk = 37

    defaultConfig {
        applicationId = "dev.zacx.muster"
        minSdk = 30
        targetSdk = 36
        versionCode = 5
        versionName = "0.1.3"
    }

    buildTypes {
        release {
            // CI never signs a release. The operator signs offline
            // (scripts/sign-release, README).
            isMinifyEnabled = false
            signingConfig = null
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":core:net"))
    implementation(project(":core:auth"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:stepup"))
    implementation(project(":feature:push"))
    implementation(project(":feature:notify"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.browser)
    implementation(libs.androidx.webkit)
    implementation(libs.androidx.work.runtime)
    implementation(libs.kotlinx.coroutines.android)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.work.testing)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.okhttp.mockwebserver)
}
