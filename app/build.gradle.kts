plugins {
    alias(libs.plugins.androidApplication)
    alias(libs.plugins.kotlinAndroid)
    alias(libs.plugins.kotlinCompose)
    alias(libs.plugins.androidxBaselineProfile)
}

android {
    namespace = "com.localllm.app"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.localllm.app"
        minSdk = 29
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }

    signingConfigs {
        create("release") {
            // Read from ~/.gradle/gradle.properties or env. NEVER hardcode.
            val keystorePath = (findProperty("LOCALLLM_KEYSTORE_PATH") as String?)
                ?: System.getenv("LOCALLLM_KEYSTORE_PATH")
            val keystorePassword = (findProperty("LOCALLLM_KEYSTORE_PASSWORD") as String?)
                ?: System.getenv("LOCALLLM_KEYSTORE_PASSWORD")
            val keyAlias = (findProperty("LOCALLLM_KEY_ALIAS") as String?)
                ?: System.getenv("LOCALLLM_KEY_ALIAS")
            val keyPassword = (findProperty("LOCALLLM_KEY_PASSWORD") as String?)
                ?: System.getenv("LOCALLLM_KEY_PASSWORD")

            if (keystorePath != null && keystorePassword != null && keyAlias != null && keyPassword != null) {
                storeFile = file(keystorePath)
                this.storePassword = keystorePassword
                this.keyAlias = keyAlias
                this.keyPassword = keyPassword
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            val releaseCfg = signingConfigs.getByName("release")
            signingConfig = if (releaseCfg.storeFile != null) releaseCfg else signingConfigs.getByName("debug")
        }
        debug {
            // unchanged
        }
    }

    splits {
        abi {
            isEnable = true
            reset()
            // LiteRT-LM 0.11.0 AAR ships JNI .so files for arm64-v8a only;
            // armeabi-v7a is intentionally excluded. x86 / x86_64 are dropped —
            // emulator inference on x86 is unusably slow anyway (see docs/development.md).
            include("arm64-v8a")
            isUniversalApk = true
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions {
        jvmTarget = "11"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.0}"
            excludes += "META-INF/INDEX.LIST"
            excludes += "META-INF/io.netty.versions.properties"
        }
        jniLibs {
            useLegacyPackaging = true
        }
    }
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.ui)
    implementation(libs.androidx.ui.graphics)
    implementation(libs.androidx.ui.tooling.preview)
    implementation(libs.androidx.material3)
    implementation(libs.androidx.material.icons.extended)
    implementation(libs.androidx.lifecycle.runtime.compose)

    // Markdown parsing (Java, no Android dependency) for assistant message rendering
    implementation(libs.commonmark)

    // Async, Flow-native settings persistence (replaces SharedPreferences)
    implementation(libs.androidx.datastore.preferences)

    // Ktor Server (OpenAI-compatible HTTP API)
    implementation(libs.ktor.server.core)
    implementation(libs.ktor.server.netty)
    implementation(libs.ktor.server.cors)
    implementation(libs.ktor.server.content.negotiation)
    implementation(libs.ktor.serialization.gson)

    // LiteRT-LM (on-device Gemma 4 / 3n via Google's LLM runtime; replaces MediaPipe tasks-genai)
    implementation(libs.litertlm.android)

    // OkHttp for the in-app Chat tab that hits the local server
    implementation(libs.okhttp)
    implementation(libs.okhttp.sse)

    // Unit tests
    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)

    // R8 consumes the profile produced by the :macrobenchmark module to
    // AOT-compile the hot startup paths in release builds.
    baselineProfile(project(":macrobenchmark"))
}
