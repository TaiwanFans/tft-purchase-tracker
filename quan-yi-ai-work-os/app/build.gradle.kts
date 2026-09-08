plugins {
    id("com.android.application")
}

android {
    namespace = "tw.com.tft.aiworkos"
    compileSdk = 36

    defaultConfig {
        applicationId = "tw.com.tft.aiworkos"
        minSdk = 26
        targetSdk = 36
        versionCode = 1
        versionName = "0.1.0"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
