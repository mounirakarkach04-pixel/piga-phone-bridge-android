plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.piga.phonebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "io.piga.phonebridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1.0"
    }
}
