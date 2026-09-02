plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "de.kalnbach.operations"
    compileSdk = 35

    defaultConfig {
        applicationId = "de.kalnbach.operations"
        minSdk = 26
        targetSdk = 35
        versionCode = 3
        versionName = "2.1.0-fieldtest"
    }

    buildTypes {
        getByName("debug") {
            applicationIdSuffix = ".fieldtest"
            versionNameSuffix = "-debug"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions { jvmTarget = "17" }
}

dependencies {}
