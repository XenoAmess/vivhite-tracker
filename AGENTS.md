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
- **所有** workflow 的 checkout 都必须 `fetch-depth: 0`（versionCode = `git rev-list --count HEAD`、versionName = `git describe --tags` 都依赖完整 git 历史，浅克隆会让前者塌成 1、后者直接报错）。
- `versionCode` = `git rev-list --count HEAD`（`app/build.gradle.kts`），单调递增保证覆盖安装；`versionName` 从最近 tag 推导（`v1.1.2` → `1.1.2`，tag 后有 N commit → `1.1.2+N`，无 tag → `0.0.0+commit数`），让 tag 与显示对齐。构建必须能在项目目录执行 git。

## 模拟器自测（每个功能改完都要做）

- **模拟器必须无窗口后台运行**（用户明确要求：不许把模拟器窗口弹到前台）。启动命令：
  `nohup $ANDROID_HOME/emulator/emulator -avd Medium_Phone_API_36.1 -no-window -no-audio -no-snapshot-load -gpu swiftshader_indirect > /tmp/emulator.log 2>&1 &`
  等开机：`adb shell getprop sys.boot_completed` 返回 1。
- 功能改动收尾后：`JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew connectedDebugAndroidTest` 跑 instrumented 套件；**视觉类改动额外截图人工核对**：
  `adb exec-out screencap -p > /tmp/screen.png` 后读图确认排版。
- pgrep 检查模拟器进程时注意：模式串会匹配到自己的 shell 命令（用 `adb devices` 判断更靠谱）。
- **模拟器 /data 写满会导致装不上测试包**（`Requested internal only, but not enough space`）：生产镜像无 root 查不了目录占用，直接 kill 后加 `-wipe-data` 重启（测试环境数据无所谓）。
- **instrumented UI 自动化踩坑**（2026-08 已踩完）：
  - androidTest 类路径没有 Robolectric，`ShadowDialog` 不可用；对话框交互用 Espresso `inRoot(isDialog())`（`matcher.RootMatchers`，不是 assertion 包）。
  - `uiautomator dump` 里 emoji 是 XML 实体（`&#128516;`），按文本匹配 chip 会扑空——用坐标点或匹配纯文本部分。
  - 首启连环权限弹窗（精确闹钟/电池优化）挡导航：`adb shell cmd appops set <pkg> SCHEDULE_EXACT_ALARM allow` + `dumpsys deviceidle whitelist +<pkg>` 预授权。
  - 非 exported Activity 不能 `am start`（SecurityException），走主 Activity 然后 UI 点进去。

## 架构：检测循环（读代码前先看这里）

```
AlarmManager(60s exact) → AlarmReceiver → startForegroundService
  → LiveCheckService.onStartCommand → checkLiveStatus → 排下一次 Alarm
```

- `BilibiliApi.checkLiveStatus()` 返回三态 `LiveStatus.Live / NotLive / Error`。Error **不更新** `lastStatus`，15 秒后重试一次；只有 NotLive→Live 跳变才触发提醒。改这里时不要把 Error 合并成 false，会污染状态导致重复/漏提醒。
- WorkManager（`LiveCheckWorker`，15min 周期）**只是服务死掉的拉起兜底，本身不做检测**。Doze 下 60s 轮询必被系统节流到 ~15min，这是平台限制，只能靠电池白名单 + 精确闹钟权限 + 国产 ROM 自启动引导缓解，代码绕不开。
- 服务状态靠 `LiveCheckService` companion 的 `@Volatile` 静态变量（`isRunning`/`lastLiveStatus`/`isUserStopped`）+ `PreferenceManager` 共享；Worker/Receiver/Activity 都读这两处。
- 重启链：`onDestroy`（非用户停止时）广播 `RESTART_SERVICE` → `ServiceRestartReceiver`；`onTaskRemoved` 排 Alarm + 一次性 Worker；`BootReceiver` 开机拉起；`PackageReplacedReceiver`（MY_PACKAGE_REPLACED）覆盖安装后拉起。四个 Receiver 捕获 `ForegroundServiceStartNotAllowedException` 后降级到一次性 WorkManager。
- `AppLogger` 写 `filesDir/logs/monitor.log`（1MB 截断），排查后台问题先让用户导出这个（应用内「查看运行日志」页）。
- 房间号/UID 单一来源在 `util/BiliTargets`（ROOM_ID / MONITOR_MID）；头像源用 `live_user/v1/Master/info`（未登录可用），`x/space/acc/info` 已被风控（-799）仅作兜底；`AnchorAvatarLoader` 带 24h 磁盘缓存。改房间号/UID 只改 BiliTargets，但通知/页面文案里的展示文本仍需全局搜。
- **场次记录进程死亡约束**（2026-08 修复后）：`recordStreamStart` 对同一场（liveStartTime 一致）幂等复用开放行；NotLive 且无跳变时静默 reconcile 残留开放行，闭合到 `last_live_observed_time`（每次 Live 检测刷新；`lastCheckTime` 每次检测含 NotLive 都覆盖，**不能**当存活证据）。这两条破了升级场景会造出 0 分钟幽灵行/数天长假场次。

## 绮迹手账（StatsActivity，原「场次记录」页）

- Room DB 当前 v3：`stream_sessions`（场次）/`stream_title_changes`（主题变化）/`mood_events`（心情事件，v2 加，v3 加 duration_min）。加表必须写 Migration，禁止删库重建。
- 心情目录在 `domain/MoodCatalog`（22 种，key→emoji+中文文案+分组），**DB 只存 key 不存 emoji**；CSV 里存「😄开心」display，导入用 `keyOf` 反查（裸 key 也认）。
- 备份编解码在 `domain/SessionBackup`（混合 CSV：类型列区分场次/心情，兼容旧 5 列格式；引号/逗号/换行转义）。导入合并去重（场次=起止时间，心情=时间+心情+标题）。
- 导出海报 `util/StatsImageRenderer`：**纯按月维度**（摘要/逐周柱状/月历热力/心情统计/魔法期/全记录），可变高度；柱状离屏复用 `WeekStreamBarsView`（柱数随数据）；纯绘制无 IO 好测试。

## 应用更新通道（UpdateChecker）

- **下载与 JSON 拉取都走 `UpdateMirrors.candidates` 镜像轮询**（ghfast.top / gh-proxy.com + 直连兜底）；只有 github.com / objects.githubusercontent.com 的 https URL 可代理，github.io（Pages）/http/本地地址一律直连。
- beta 通道 = `beta-archive` 滚动 release 的固定资产（`version.json` + `beta-latest.apk`，CI `build_beta_chains.py` 维护）；Pages 是 legacy 回退。
- stable 检查 api.github.com 失败时回退 302 免 API 路径（releases/latest 取 Location 提 tag → 拼资产 URL）。
- **`UpdateChecker` 必须保持纯 JVM 可测**：不得引 `AppLogger`/`android.util.Log`（UpdateCheckerTest 无 Robolectric，会炸 not-mocked）。

## 测试坑（已踩过）

- 像素级断言需 `@GraphicsMode(GraphicsMode.Mode.NATIVE)`（LEGACY 模式 Canvas 只记录不渲染）；nativeruntime 已在依赖链。
- 单测堆 `maxHeapSize="1g"`（build.gradle.kts）：大图 PNG 解码在默认堆下随机 `Resources$NotFoundException`（img_off 已缩 512×512，别再往 res 塞 MB 级大图）。
- 深色模式：**禁止硬编码文字色**（`#1A1A1A` 之类），用 `?android:attr/textColorPrimary/Secondary`；浅色底上的文字（如粉底魔法期格）例外并保持深色。

## CI / 仓库约定

- master 受保护：必需状态检查为 `build`（android-ci.yml job 名）+ `Instrumented tests (emulator)`（android-test job 显示名），strict + 线性历史。改 CI workflow 的 job 名/matrix 后必须同步更新 branch protection（gh api PATCH .../protection/required_status_checks）。
- Dependabot：`.github/dependabot.yml`（**gradle**（不是 maven，本项目是 Gradle 没有 pom.xml）+ github-actions，每周一 04:00 Asia/Shanghai）。`auto-merge.yml` 自动合并 patch/minor 及 github-actions 的 major，maven major 留人工。`MYTOKEN` 和 `DEBUG_KEYSTORE_BASE64` 两个 secret 都在 **dependabot** namespace（`gh secret list --app dependabot` 才能看到）——dependabot PR 的 workflow 读不到 actions namespace 的 secret，只放一边会导致 keystore 解码成空文件、签名报 `Tag number over 30 is not supported`。
- 提交信息：Conventional Commits，中英文混用均可（如 `fix(service): 修复...`、`ci: ...`）。
- 改动后如无特殊说明，立刻自动 commit + push，不用等用户确认。
- `kimi.md` 是开发者的个人便签，不是项目约定，别当真也别删。

## 详细背景文档

- `docs/background-detection-fix-plan.md` — Doze/国产 ROM 后台失效的完整诊断与修复方案
- `docs/dependabot-optimization-notes.md` — dependabot/CI 配置决策记录
- `docs/feature-blacklist.md` — **已否决需求黑名单**：提新功能建议前先查这里，列在里面的不要再提
