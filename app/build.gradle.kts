plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.demo.shelfcounter"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.demo.shelfcounter"
        minSdk = 26
        targetSdk = 35
        versionCode = 29
        versionName = "2.9"

        // Samsung A56 and nearly all current phones are 64-bit ARM; keeps the APK small.
        ndk {
            abiFilters += listOf("arm64-v8a", "armeabi-v7a")
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            // Demo only: sign the release build with the debug key so it installs directly.
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
    val camerax = "1.4.1"

    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-ktx:1.9.3")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("com.google.android.material:material:1.12.0")

    // CameraX (preview, tap-to-focus, pinch-zoom, torch) + ML Kit bridge
    implementation("androidx.camera:camera-camera2:$camerax")
    implementation("androidx.camera:camera-lifecycle:$camerax")
    implementation("androidx.camera:camera-view:$camerax")
    implementation("androidx.camera:camera-mlkit-vision:$camerax")

    // ML Kit barcode scanning, bundled model: works offline, no Play Services download
    implementation("com.google.mlkit:barcode-scanning:17.3.0")

    // ML Kit object detection, bundled model: finds the first item of the row
    implementation("com.google.mlkit:object-detection:17.0.2")

    // OpenCV (SIFT keypoints) for counting identical items by shape
    implementation("org.opencv:opencv:4.10.0")
}
