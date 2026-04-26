plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "com.bilibili.livemonitor"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.bilibili.livemonitor"
        minSdk = 26
        targetSdk = 34
        // 使用 Git commit 数作为 versionCode，确保 CI 构建可以覆盖安装
        versionCode = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
        versionName = "1.0.${versionCode}"
    }

    signingConfigs {
        create("release") {
            val keyFilePath = System.getenv("SIGNING_KEY_FILE")
            storeFile = if (keyFilePath != null) {
                file(keyFilePath)
            } else {
                file("release.keystore")
            }
            storePassword = System.getenv("KEY_STORE_PASSWORD") ?: ""
            keyAlias = System.getenv("ALIAS") ?: ""
            keyPassword = System.getenv("KEY_PASSWORD") ?: ""
        }
        getByName("debug") {
            // CI 环境下从 Secrets 注入的固定 debug.keystore，确保多机签名一致
            // 本地若无此文件则回退到默认 ~/.android/debug.keystore
            val debugKeyFile = file("debug.keystore")
            if (debugKeyFile.exists()) {
                storeFile = debugKeyFile
                storePassword = System.getenv("DEBUG_KEY_STORE_PASSWORD") ?: "android"
                keyAlias = System.getenv("DEBUG_KEY_ALIAS") ?: "androiddebugkey"
                keyPassword = System.getenv("DEBUG_KEY_PASSWORD") ?: "android"
            }
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            signingConfig = signingConfigs.getByName("release")
        }
        debug {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlin {
        compilerOptions {
            jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
        }
    }
    buildFeatures {
        viewBinding = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.12.0")
    implementation("androidx.appcompat:appcompat:1.6.1")
    implementation("com.google.android.material:material:1.11.0")
    implementation("androidx.constraintlayout:constraintlayout:2.1.4")
    implementation("androidx.work:work-runtime-ktx:2.9.0")
    implementation("org.jsoup:jsoup:1.17.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.7.3")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.7.0")
}
