import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "app.snapfade"
    compileSdk = 35

    defaultConfig {
        applicationId = "app.snapfade"
        minSdk = 30 // MANAGE_EXTERNAL_STORAGE / isExternalStorageManager() need API 30
        targetSdk = 35
        versionCode = 4
        versionName = "0.4"
    }

    signingConfigs {
        create("release") {
            val props = rootProject.file("local.properties").let { f ->
                Properties().apply { if (f.exists()) f.inputStream().use { load(it) } }
            }
            storeFile = file(props.getProperty("RELEASE_STORE_FILE", "../release.keystore"))
            storePassword = props.getProperty("RELEASE_STORE_PASSWORD", "")
            keyAlias = props.getProperty("RELEASE_KEY_ALIAS", "")
            keyPassword = props.getProperty("RELEASE_KEY_PASSWORD", "")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            // F-Droid strips the signingConfigs block and has no keystore; it verifies the unsigned output
            // against the GitHub release, so this must resolve to null in both cases
            signingConfig = signingConfigs.findByName("release")?.takeIf { it.storeFile?.exists() == true }
            vcsInfo.include = false // embedded git metadata would break reproducible builds
        }
        debug {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
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
