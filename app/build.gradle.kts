plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "org.hermes.hyperfcmfix"
    compileSdk = 35

    defaultConfig {
        applicationId = "org.hermes.hyperfcmfix"
        minSdk = 30
        targetSdk = 35
        versionCode = 3
        versionName = "3.0-api102"
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
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

dependencies {
    compileOnly("io.github.libxposed:api:102.0.0")
}
