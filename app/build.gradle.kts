plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
}

// Large binaries are not committed (see tools/models.sha256). Fail with the fix
// command instead of a cryptic "module not found" / "file not found" error.
val missingModels = listOf(
    "libs/sherpa-onnx.aar",
    "src/main/assets/asr/encoder.int8.onnx",
    "src/main/assets/asr/decoder.int8.onnx",
    "src/main/assets/asr/joiner.int8.onnx",
    "src/main/assets/asr/tokens.txt",
    "src/main/assets/kws/encoder.int8.onnx",
    "src/main/assets/kws/decoder.onnx",
    "src/main/assets/kws/joiner.int8.onnx",
    "src/main/assets/kws/en.phone",
    "src/main/assets/kws/tokens.txt",
    "src/main/assets/vad/silero_vad.onnx",
).filterNot { file(it).exists() }

if (missingModels.isNotEmpty()) {
    throw GradleException(
        "缺少构建所需的模型 / 原生库（${missingModels.size} 个）：\n" +
            missingModels.joinToString("\n") { "  - app/$it" } +
            "\n\n先运行：\n  bash tools/fetch_models.sh\n" +
            "（国内网络可加 GH_PROXY，例如 GH_PROXY=https://ghfast.top）\n" +
            "详见 README「快速开始」。"
    )
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
        // p3：KWS 离线自检走 BuildConfig.DEBUG 分支，AGP 8+ 默认不生成该类
        buildConfig = true
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
    // p5 记忆层：Room + FTS4（目标机 sqlite 3.28 无 FTS5，只有 FTS4）
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)
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
