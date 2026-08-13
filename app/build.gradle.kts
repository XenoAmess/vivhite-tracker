plugins {
    id("com.android.application")
    id("com.google.devtools.ksp")
    jacoco
}

// Room schema 导出入仓（迁移测试 MigrationTestHelper 依赖 schema JSON）
ksp {
    arg("room.schemaLocation", "$projectDir/schemas")
}

jacoco {
    toolVersion = "0.8.12"
}

// ========== 版本推导（单一来源，供 Gradle 与 CI workflow 共用）==========
// versionCode：Git commit 数，单调递增，保证 CI 构建可覆盖安装
val gitVersionCode = providers.exec {
    commandLine("git", "rev-list", "--count", "HEAD")
}.standardOutput.asText.get().trim().toInt()
// versionName：从最近 tag 推导，让 tag 与显示对齐。
// HEAD 在 tag 上 → "1.1.2"；tag 后有 N commit → "1.1.2+N"；无 tag → "0.0.0+N"
val gitVersionName = run {
    val describe = providers.exec {
        commandLine("git", "describe", "--tags", "--long", "--match", "v*")
    }.standardOutput.asText.get().trim()
    val match = Regex("v(.+)-(\\d+)-g[0-9a-f]+").matchEntire(describe)
    if (match != null) {
        val base = match.groupValues[1]
        val ahead = match.groupValues[2].toInt()
        if (ahead == 0) base else "$base+$ahead"
    } else {
        "0.0.0+$gitVersionCode"
    }
}
// 8 位 git 哈希，用于首页版本信息展示
val gitHash = providers.exec {
    commandLine("git", "rev-parse", "--short=8", "HEAD")
}.standardOutput.asText.get().trim()

// 把版本信息写进 build/outputs/version-info.properties，CI workflow 从这里读取，
// 消除 android-release.yml / android-ci.yml / 增量脚本 里各自复刻的版本推导。
// 目标 File 在配置期算成 java.io.File 再进 doLast，避免捕获 Provider 破坏 configuration cache
// configuration cache 兼容：doLast 闭包只许捕获可序列化局部变量
//（顶层脚本 val 会把脚本对象带进闭包 → 不可序列化）
val writeVersionInfo = tasks.register("writeVersionInfo") {
    val outFile = layout.buildDirectory.file("outputs/version-info.properties").get().asFile
    val content = "versionCode=$gitVersionCode\nversionName=$gitVersionName\ngitHash=$gitHash\n"
    outputs.file(outFile)
    doLast {
        outFile.writeText(content)
    }
}

android {
    namespace = "com.bilibili.livemonitor"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.bilibili.livemonitor"
        minSdk = 26
        targetSdk = 36
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        versionCode = gitVersionCode
        versionName = gitVersionName
        // 8 位 git 哈希，用于首页版本信息展示
        buildConfigField(
            "String",
            "GIT_HASH",
            "\"$gitHash\""
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
    lint {
        // 存量 372 个 warning 冻结到 baseline，只拦新增问题（Agenda 2026-08-13）
        baseline = file("lint-baseline.xml")
    }
    testOptions {
        unitTests.isIncludeAndroidResources = true
        // Robolectric 加载大图/多沙箱吃堆内存：默认堆偏紧时 PNG 解码会
        // 随机抛 Resources$NotFoundException（2026-08 CI 实发），给足余量
        unitTests.all { it.maxHeapSize = "1g" }
    }
}

dependencies {
    implementation(files("libs/open_sdk_3.5.19_r9483ffc7_lite.jar"))
    implementation("androidx.core:core-ktx:1.18.0")
    implementation("androidx.appcompat:appcompat:1.7.1")
    implementation("com.google.android.material:material:1.14.0")
    implementation("androidx.constraintlayout:constraintlayout:2.2.2")
    implementation("androidx.work:work-runtime-ktx:2.11.2")
    implementation("org.jsoup:jsoup:1.23.1")
    // 长宣传图的直播间二维码（纯 JVM，QR 矩阵可单测）
    implementation("com.google.zxing:core:3.5.4")
    // 增量更新打补丁（ApkDiffPatch 的 Android JNI 库，libapkpatch.so 已打入 jniLibs 4 ABI）
    // jbsdiff（io.sigpipe）已随稳定版/beta 双通道切 ApkDiffPatch 后移除
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-android:1.11.0")
    // 媒体播放器（ExoPlayer/Media3 - 用于 alarm 铃声 gapless 循环，解决 MediaPlayer 循环间隔）
    implementation("androidx.media3:media3-exoplayer:1.5.1")
    // 场次/统计存储（Room + KSP，实体不用 @Parcelize——AGP9 内置 Kotlin 下 KSP 已知 bug）
    implementation("androidx.room:room-runtime:2.8.4")
    implementation("androidx.room:room-ktx:2.8.4")
    ksp("androidx.room:room-compiler:2.8.4")
    testImplementation("androidx.room:room-testing:2.8.4")
    testImplementation("junit:junit:4.13.2")
    testImplementation("org.json:json:20260719")
    testImplementation("org.robolectric:robolectric:4.16.1")
    testImplementation("androidx.test:core:1.7.0")
    testImplementation("androidx.work:work-testing:2.11.2")
    androidTestImplementation("androidx.test.ext:junit:1.3.0")
    androidTestImplementation("androidx.test:runner:1.7.0")
    // Room 迁移测试（MigrationTestHelper 读 schemas/ 里的版本化 schema JSON）
    androidTestImplementation("androidx.room:room-testing:2.8.4")
    // 手账 UI 自动化（对话框交互）
    androidTestImplementation("androidx.test.espresso:espresso-core:3.7.0")
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
        // 不用 providers.exec（捕获 Project 引用破坏 configuration cache），
        // 改用 ProcessBuilder 在任务动作内跑 git
        fun git(vararg args: String): String = runCatching {
            val pb = ProcessBuilder(listOf("git", *args))
            // 本机 shell 环境给子进程注了 proxychains（LD_PRELOAD），启动横幅会
            // 混进 git 输出污染 changelog——摘掉它（本地 git 操作不需要代理）
            pb.environment().remove("LD_PRELOAD")
            pb.redirectErrorStream(true)
            val proc = pb.start()
            proc.outputStream.close()
            val output = proc.inputStream.readBytes().toString(Charsets.UTF_8)
            proc.waitFor()
            // 防御性过滤：任何非 git 输出的工具横幅行都不进 changelog
            output.lines()
                .filter { it.isNotBlank() && !it.startsWith("[proxychains") }
                .joinToString("\n")
                .trim()
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
android.sourceSets.getByName("main").assets.directories.add(changelogDir.get().asFile.absolutePath)
// Room schema JSON 作为 androidTest assets（MigrationTestHelper 按版本读）
android.sourceSets.getByName("androidTest").assets.directories.add("$projectDir/schemas")
tasks.named("preBuild").configure {
    dependsOn(generateChangelog)
    dependsOn(writeVersionInfo)
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
