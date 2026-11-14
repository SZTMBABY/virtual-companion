plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

android {
    namespace = "com.example.virtualcompanion"
    compileSdk {
        version = release(37)
    }

    defaultConfig {
        applicationId = "com.example.virtualcompanion"
        minSdk = 30
        targetSdk = 37
        versionCode = 1
        versionName = "1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"

        ndk {
            // The vendored sherpa-onnx AAR ships 4 ABIs; the target device is arm64-v8a.
            // Without this filter the APK triples in size.
            abiFilters += "arm64-v8a"
        }
    }

    buildTypes {
        release {
            optimization {
                enable = true
                packageScope = setOf("androidx.**", "kotlin.**", "kotlinx.**")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
    androidResources {
        // Live2D moc3/physics/onnx must stay byte-exact; do not let AGP re-compress them.
        noCompress += listOf("onnx", "moc3", "model3.json", "exp3.json", "cdi3.json", "physics3.json")
    }
    packaging {
        jniLibs {
            // sherpa-onnx .so are already stripped upstream; keep only arm64.
            useLegacyPackaging = false
        }
        resources {
            excludes += listOf("META-INF/*.kotlin_module", "META-INF/DEPENDENCIES")
        }
    }
}

dependencies {
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.okhttp)
    implementation(libs.androidx.documentfile)
    // Vendored from JitPack: com.github.k2-fsa.sherpa-onnx:sherpa-onnx:v2.0.1
    implementation(files("libs/sherpa-onnx.aar"))
    testImplementation(libs.junit)
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
    debugImplementation(libs.androidx.compose.ui.tooling)
}
