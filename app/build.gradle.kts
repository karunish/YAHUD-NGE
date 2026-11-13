plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.hud01"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.hud01"
        minSdk = 30
        targetSdk = 35
        versionCode = 6
        versionName = "0.6.0"
    }

    // Fixed debug key committed to the repo so every CI build is signed the same
    // and new APKs install over old ones. Debug use only.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
