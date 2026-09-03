plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.pigapocket.bootstrap"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pigapocket.aeiou"
        minSdk = 26
        targetSdk = 35
        versionCode = 10002
        versionName = "1.0.0-rc2"
    }

    buildTypes {
        getByName("debug") {
            isMinifyEnabled = false
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
