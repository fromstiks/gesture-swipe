plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.example.gestureswipe"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.example.gestureswipe"
        minSdk = 26
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"

        // Only ship arm64 native libs (modern phones incl. OnePlus 9) — much smaller APK,
        // and keeps it under Telegram's 50 MB bot upload limit.
        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    // Fixed key so every build shares one signature → updates install over each other.
    // The keystore is never committed; CI writes it from a GitHub Actions secret.
    val stableKeystore = file("stable.keystore")
    val hasStableKeystore = stableKeystore.exists()
    signingConfigs {
        if (hasStableKeystore) {
            create("stable") {
                storeFile = stableKeystore
                storePassword = "gesturepass"
                keyAlias = "gestureswipe"
                keyPassword = "gesturepass"
            }
        }
    }

    buildTypes {
        debug {
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
        }
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            if (hasStableKeystore) signingConfig = signingConfigs.getByName("stable")
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_1_8
        targetCompatibility = JavaVersion.VERSION_1_8
    }
    kotlinOptions {
        jvmTarget = "1.8"
    }
    buildFeatures {
        viewBinding = true
    }

    // MediaPipe .task models must not be compressed inside the APK
    androidResources {
        noCompress += "task"
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")

    // CameraX
    val cameraxVersion = "1.3.4"
    implementation("androidx.camera:camera-core:$cameraxVersion")
    implementation("androidx.camera:camera-camera2:$cameraxVersion")
    implementation("androidx.camera:camera-lifecycle:$cameraxVersion")
    implementation("androidx.camera:camera-view:$cameraxVersion")

    // Lifecycle (LifecycleService for camera-in-service)
    implementation("androidx.lifecycle:lifecycle-service:2.8.4")

    // MediaPipe Tasks Vision (HandLandmarker)
    implementation("com.google.mediapipe:tasks-vision:0.10.14")
}
