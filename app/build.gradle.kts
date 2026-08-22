plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.piga.phonebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.piga.phonebridge.mobile"
        minSdk = 26
        targetSdk = 35
        versionCode = 12
        versionName = "0.1.11"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
    }
}
