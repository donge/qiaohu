plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.donge.qiaohu"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.donge.qiaohu"
        minSdk = 29
        targetSdk = 35
        versionCode = 2
        versionName = "0.1.1"

        ndk {
            abiFilters += listOf("arm64-v8a")
        }
    }

    signingConfigs {
        create("release") {
            storeFile = file("qiaohu-release.jks")
            storePassword = "qiaohu123"
            keyAlias = "qiaohu"
            keyPassword = "qiaohu123"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            isDebuggable = true
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    kotlinOptions {
        jvmTarget = "17"
        // litertlm-android is compiled with newer Kotlin metadata; skip the version check.
        freeCompilerArgs += listOf("-Xskip-metadata-version-check")
    }

    buildFeatures {
        compose = true
    }

    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
        }
        // Required for LiteRT-LM native libs
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    // AndroidX core
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.ktx)

    // Compose BOM
    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.tooling.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    debugImplementation(libs.compose.ui.tooling)

    // Coroutines
    implementation(libs.kotlinx.coroutines.android)

    // LiteRT-LM (LLM inference)
    implementation(libs.litertlm.android)

    // ONNX Runtime (TTS + VAD)
    implementation(libs.onnxruntime.android)

    // CameraX
    implementation(libs.camera.core)
    implementation(libs.camera.camera2)
    implementation(libs.camera.lifecycle)
    implementation(libs.camera.view)
}
