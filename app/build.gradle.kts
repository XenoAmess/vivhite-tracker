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
        // versionCode：Git commit 数，单调递增，保证 CI 构建可覆盖安装
        versionCode = providers.exec { commandLine("git", "rev-list", "--count", "HEAD") }.standardOutput.asText.get().trim().toInt()
        // versionName：从最近 tag 推导，让 tag 与显示对齐。
        // HEAD 在 tag 上 → "1.1.2"；tag 后有 N commit → "1.1.2+N"；
        // 无 tag → "0.0.0+${commit数}"（首次构建前边界）
        versionName = run {
            val describe = providers.exec {
                commandLine("git", "describe", "--tags", "--long", "--match", "v*")
            }.standardOutput.asText.get().trim()
            val match = Regex("v(.+)-(\\d+)-g[0-9a-f]+").matchEntire(describe)
            if (match != null) {
                val base = match.groupValues[1]
                val ahead = match.groupValues[2].toInt()
                if (ahead == 0) base else "$base+$ahead"
            } else {
                "0.0.0+$versionCode"
            }
        }
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
    implementation(files("libs/open_sdk_3.5.19_r9483ffc7_lite.jar"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.1")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jsoup:jsoup:1.23.1")
    // 长宣传图的直播间二维码（纯 JVM，QR 矩阵可单测）
    implementation("com.google.zxing:core:3.5.3")
    // 增量更新打补丁（bsdiff 的 Java 移植，纯 JVM 无 ABI 分裂）
    implementation("io.sigpipe:jbsdiff:1.0")
    // jbsdiff 的 bzip2 依赖（它传递的是 2013 年的 1.5，显式提到新版本）
    implementation("org.apache.commons:commons-compress:1.28.0")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // 媒体播放器（ExoPlayer/Media3 - 用于 alarm 铃声 gapless 循环，解决 MediaPlayer 循环间隔）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    implementation("androidx.media3:media3-common:1.5.1")
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

// 更新日志打包：遍历 v* tag 生成每版更新说明进 assets/CHANGELOG.txt（关于页展示）。
// 输出随构建目录清理，不污染源码树。无 tag/无 git（浅克隆）写兜底文案，构建不挂。
val changelogDir = layout.buildDirectory.dir("generated/changelog/assets")
val generateChangelog = tasks.register("generateChangelog") {
    val outFile = changelogDir.get().file("CHANGELOG.txt").asFile
    outputs.file(outFile)
    outputs.upToDateWhen { false } // git 历史每次构建都可能变
    doLast {
        outFile.parentFile.mkdirs()
        fun git(vararg args: String): String = runCatching {
            providers.exec { commandLine("git", *args) }.standardOutput.asText.get().trim()
        }.getOrDefault("")
        val tags = git("tag", "-l", "v*", "--sort=-creatordate")
            .lines().filter { it.isNotBlank() }
        if (tags.isEmpty()) {
            outFile.writeText("暂无历史版本日志\n", Charsets.UTF_8)
            return@doLast
        }
        val sb = StringBuilder()
        tags.forEachIndexed { index, tag ->
            val date = git("log", "-1", "--format=%cs", tag)
            sb.append("## $tag ($date)\n")
            val range = if (index + 1 < tags.size) "${tags[index + 1]}..$tag" else tag
            git("log", "--oneline", "--no-decorate", range)
                .lines().filter { it.isNotBlank() }.take(20)
                .forEach { sb.append("$it\n") }
            sb.append("\n")
        }
        outFile.writeText(sb.toString(), Charsets.UTF_8)
    }
}
android.sourceSets.getByName("main").assets.srcDir(changelogDir.get())
tasks.named("preBuild").configure { dependsOn(generateChangelog) }

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
