plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.bohanli.ruzhtranslator"
    compileSdk {
        version = release(36) {
            minorApiLevel = 1
        }
    }

    defaultConfig {
        applicationId = "com.bohanli.ruzhtranslator"
        minSdk = 29
        targetSdk = 36
        versionCode = 32
        versionName = "3.2"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            abiFilters += "arm64-v8a"
        }
    }

    signingConfigs {
        getByName("debug") // default debug keystore
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    externalNativeBuild {
        cmake {
            path = file("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }
    buildFeatures {
        viewBinding = true
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            excludes += "**/libOpenCL.so"
        }
    }
    // Prevent Gradle from compressing model weight files.
    // Without this, multi-GB assets cause extremely slow builds and extraction.
    androidResources {
        noCompress += listOf("bin", "onnx", "model", "bpe", "gguf")
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.appcompat)
    implementation(libs.material)
    implementation(libs.androidx.constraintlayout)
    implementation(libs.kotlinx.coroutines.android)
    implementation(libs.vosk.android)
    implementation(libs.onnxruntime.android)

    // Room database (translation history)
    val roomVersion = "2.7.1"
    implementation("androidx.room:room-runtime:$roomVersion")
    implementation("androidx.room:room-ktx:$roomVersion")
    ksp("androidx.room:room-compiler:$roomVersion")

    testImplementation(libs.junit)
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.espresso.core)
}
