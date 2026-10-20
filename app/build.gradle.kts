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
        versionCode = 1
        versionName = "0.0.1"
    }

    buildTypes {
        release {
            // CI never signs a release. The operator signs offline (README).
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
}

dependencies {
    implementation(project(":core:model"))
    implementation(project(":feature:shell"))
    implementation(project(":feature:stepup"))
    implementation(project(":feature:notify"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.material3)

    testImplementation(libs.junit)
}
