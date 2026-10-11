plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.tempss"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.tempss"
        minSdk = 30 // AccessibilityService.takeScreenshot() needs API 30
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
    }

    buildTypes {
        release { isMinifyEnabled = false }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

kotlin {
    compilerOptions { jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17) }
}

dependencies {
    implementation("androidx.core:core-ktx:1.15.0")        // FileProvider, contentValuesOf
    implementation("androidx.work:work-runtime-ktx:2.10.0") // 6 h expiry
}
