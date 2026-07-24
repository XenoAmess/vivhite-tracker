plugins {
    id("com.android.application")
    jacoco
}

jacoco {
    toolVersion = "0.8.12"
}

android {
    namespace = "com.bilibili.livemonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bilibili.livemonitor"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // 使用 Git commit 数作为 versionCode，确保 CI 构建可以覆盖安装
        versionCode = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
        versionName = "1.0.${versionCode}"
        // 8 位 git 哈希，用于首页版本信息展示
        buildConfigField(
            "String",
            "GIT_HASH",
            "\"${providers.exec { commandLine("git", "rev-parse", "--short=8", "HEAD") }.standardOutput.asText.get().trim()}\""
        )
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
            if (debugKeyFile.exists() && debugKeyFile.length() > 0) {
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
            enableUnitTestCoverage = true
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
        buildConfig = true
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
    }
}

dependencies {
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jsoup:jsoup:1.22.2")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    implementation("androidx.lifecycle:lifecycle-runtime-ktx:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
}

tasks.withType<Test>().configureEach {
    // Robolectric 沙箱类加载器重写字节码会绕过 JaCoCo agent，
    // includeNoLocationClasses 让其覆盖率也能被统计
    extensions.configure(org.gradle.testing.jacoco.plugins.JacocoTaskExtension::class.java) {
        setIncludeNoLocationClasses(true)
        excludes = listOf("jdk.internal.*")
    }
}

tasks.register<JacocoReport>("jacocoUnitTestReport") {
    dependsOn("testDebugUnitTest")
    reports {
        xml.required.set(true)
        html.required.set(true)
        xml.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/jacoco.xml"))
        html.outputLocation.set(file("${layout.buildDirectory.get()}/reports/jacoco/html"))
    }
    val fileFilter = listOf(
        "**/R.class", "**/R$*.class", "**/BuildConfig.*", "**/Manifest*.*",
        "**/*databinding/**", "**/databinding/*Binding*.class"
    )
    classDirectories.setFrom(
        fileTree("${layout.buildDirectory.get()}/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes") { exclude(fileFilter) }
    )
    sourceDirectories.setFrom("src/main/java")
    executionData.setFrom(
        fileTree("${layout.buildDirectory.get()}") {
            include("outputs/unit_test_code_coverage/debugUnitTest/*.exec")
        }
    )
}
