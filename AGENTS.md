# AGENTS.md

Android 单模块 Kotlin 应用：监控 B 站直播间 11258892（白绮）开播状态并响铃提醒。包名 `com.bilibili.livemonitor`，应用名「牢白播了吗」。

## 构建与验证

```bash
# 本地验证（与 CI 一致，按此顺序）
./gradlew lintDebug testDebugUnitTest assembleDebug
```

- 单测在 `app/src/test`（JUnit4 + Robolectric，sdk 锁 35）。覆盖率：`./gradlew jacocoUnitTestReport`，报告在 `app/build/reports/jacoco/`。Robolectric 覆盖率依赖 `includeNoLocationClasses`（build.gradle.kts 里已配，别删）。
- instrumented test 在 `app/src/androidTest`，本地跑需模拟器/真机：`./gradlew connectedDebugAndroidTest`；CI 有独立 `android-test` job（不在必需检查里）。
- **状态决策逻辑在 `domain/LiveStateDecider`**（纯函数）：提醒/状态恢复/重试。改提醒行为先改这里并补 `LiveStateDeciderTest`。
- **prefs 的 `serviceRunning` 是监控开关的唯一权威**：`LiveCheckService.onCreate/onStartCommand` 在 prefs=false 时必须自毁（防止 START_STICKY 重投/残留任务在用户停止后复活监控）；`onDestroy` 只在用户停止时才能清这个标记。改服务生命周期时这三处约束不能破。
- 发布：打 `v*` tag 触发 `.github/workflows/android-release.yml`。

## 工具链（2026-07-24 迁移后）

- **AGP 9.3.1 + 内置 Kotlin**：没有也不得有外部 `org.jetbrains.kotlin.android` 插件——AGP 9 默认注册 `kotlin` 扩展，加外部 KGP 会直接冲突报 `Cannot add extension with name 'kotlin'`。Kotlin 版本随 AGP 走。
- `compileSdk`/`targetSdk` = 36，`minSdk` = 26，JDK 17，Gradle 9.6.1。
- JaCoCo 的 classDirectories 必须指向内置 Kotlin 输出 `app/build/intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`（旧外部 KGP 的 `tmp/kotlin-classes/debug` 已不存在），否则报告 0%。

## 环境坑（已验证）

- **不要用 GraalVM 构建**：本机默认 `JAVA_HOME=graalvm-ce-25` 会在 `compileDebugJavaWithJavac` 的 `JdkImageTransform`（jlink）处失败。用 JDK 17，例如：
  `JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew lintDebug assembleDebug`
  CI 用 temurin 17，无此问题。
- **签名**：`app/debug.keystore` 存在时 Gradle 用它签名（默认 android 密码），保证 CI 与本地 APK 签名一致可覆盖安装。CI 从 secret `DEBUG_KEYSTORE_BASE64` 解码生成该文件。不要删除本地这个文件。**release 与 debug 复用同一份 keystore**（`SIGNING_KEY`=同内容 base64 + `KEY_STORE_PASSWORD`/`ALIAS`/`KEY_PASSWORD`=android 默认值），release/debug APK 可互相覆盖安装。
- **仓库默认 workflow 权限是 read**：新建 workflow 需要写操作（建 release、读写 PR、部署 Pages）时必须显式声明 `permissions:`，否则报 `Resource not accessible by integration`。
- release workflow 的 checkout 必须 `fetch-depth: 0`（versionCode 依赖完整 git 历史）。
- `versionCode` = `git rev-list --count HEAD`（`app/build.gradle.kts`），构建必须能在项目目录执行 git。

## 架构：检测循环（读代码前先看这里）

```
AlarmManager(60s exact) → AlarmReceiver → startForegroundService
  → LiveCheckService.onStartCommand → checkLiveStatus → 排下一次 Alarm
```

- `BilibiliApi.checkLiveStatus()` 返回三态 `LiveStatus.Live / NotLive / Error`。Error **不更新** `lastStatus`，15 秒后重试一次；只有 NotLive→Live 跳变才触发提醒。改这里时不要把 Error 合并成 false，会污染状态导致重复/漏提醒。
- WorkManager（`LiveCheckWorker`，15min 周期）**只是服务死掉的拉起兜底，本身不做检测**。Doze 下 60s 轮询必被系统节流到 ~15min，这是平台限制，只能靠电池白名单 + 精确闹钟权限 + 国产 ROM 自启动引导缓解，代码绕不开。
- 服务状态靠 `LiveCheckService` companion 的 `@Volatile` 静态变量（`isRunning`/`lastLiveStatus`/`isUserStopped`）+ `PreferenceManager` 共享；Worker/Receiver/Activity 都读这两处。
- 重启链：`onDestroy`（非用户停止时）广播 `RESTART_SERVICE` → `ServiceRestartReceiver`；`onTaskRemoved` 排 Alarm + 一次性 Worker；`BootReceiver` 开机拉起。三个 Receiver 捕获 `ForegroundServiceStartNotAllowedException` 后降级到一次性 WorkManager。
- `AppLogger` 写 `filesDir/logs/monitor.log`（1MB 截断），排查后台问题先让用户导出这个（应用内「查看运行日志」页）。
- 房间号 11258892 硬编码在多处：MainActivity、LiveCheckService、PreferenceManager、通知文案。改房间号要全改。

## CI / 仓库约定

- master 受保护：必需状态检查名为 `build`（android-ci.yml 的 job 名），strict + 线性历史。改 CI workflow 的 job 名/matrix 后必须同步更新 branch protection。
- Dependabot：`.github/dependabot.yml`（**gradle**（不是 maven，本项目是 Gradle 没有 pom.xml）+ github-actions，每周一 04:00 Asia/Shanghai）。`auto-merge.yml` 自动合并 patch/minor 及 github-actions 的 major，maven major 留人工。`MYTOKEN` 和 `DEBUG_KEYSTORE_BASE64` 两个 secret 都在 **dependabot** namespace（`gh secret list --app dependabot` 才能看到）——dependabot PR 的 workflow 读不到 actions namespace 的 secret，只放一边会导致 keystore 解码成空文件、签名报 `Tag number over 30 is not supported`。
- 提交信息：Conventional Commits，中英文混用均可（如 `fix(service): 修复...`、`ci: ...`）。
- 改动后如无特殊说明，立刻自动 commit + push，不用等用户确认。
- `kimi.md` 是开发者的个人便签，不是项目约定，别当真也别删。

## 详细背景文档

- `docs/background-detection-fix-plan.md` — Doze/国产 ROM 后台失效的完整诊断与修复方案
- `docs/dependabot-optimization-notes.md` — dependabot/CI 配置决策记录
