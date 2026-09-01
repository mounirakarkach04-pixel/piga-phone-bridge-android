plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "io.piga.phonebridge"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.pigapocket.bridge"
        minSdk = 26
        targetSdk = 35
        versionCode = 20
        versionName = "1.0.3"

        vectorDrawables {
            useSupportLibrary = false
        }
    }

    buildFeatures {
        buildConfig = true
    }

    buildTypes {
        getByName("debug") {
            // The bridge is a companion runtime and must never replace the
            // canonical PIGA Pocket application (com.pigapocket.enterprise).
            // The debug keystore keeps direct device testing installable
            // without granting any publication authority.
        }
        getByName("release") {
            isDebuggable = false
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro",
            )
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf("-Xjsr305=strict")
    }

    packaging {
        resources.excludes += setOf(
            "META-INF/AL2.0",
            "META-INF/LGPL2.1",
        )
    }
}

dependencies {
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20240303")
}
