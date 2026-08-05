# Coverage Report Notes — XenoAmess/vivhite-tracker

**日期**: 2026-07-23

## 项目形态

- Android 单模块 Kotlin App（JDK 17；工具链已于 2026-07-24 迁移到 **AGP 9.3.1 + 内置 Kotlin + compileSdk/targetSdk 36**，见文末「AGP 9 迁移」），**不是 Maven，不是 Vitest**
- 应用本体是前台服务 + Receiver + Activity 的 Android 框架胶水，可单测的纯逻辑很少
- 实施前 `app/src/test` **不存在**，没有任何测试

## 实施方案

- `BilibiliApi` 提取纯解析函数 `parseApiResponse` / `parseScriptContent`（行为不变，仅可测化）
- 新增 12 个 `BilibiliApiTest` 用例（JUnit 4）
- `app/build.gradle.kts`：
  - `jacoco` plugin + `toolVersion = "0.8.12"`
  - `debug { enableUnitTestCoverage = true }`
  - `tasks.register<JacocoReport>("jacocoUnitTestReport")`：exec 来自
    `outputs/unit_test_code_coverage/debugUnitTest/*.exec`，class 来自
    `intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`（AGP 9 内置 Kotlin
    的输出路径；旧外部 KGP 的 `tmp/kotlin-classes/debug` 已不存在），排除 R/BuildConfig/databinding
- CI（android-ci.yml）：`jacocoUnitTestReport` → 上传 `coverage-report-${{ github.sha }}` →
  `coverage-pages` job（`if: master push`，`needs: [build]`）解析 jacoco.xml 的
  INSTRUCTION counter 生成 `coverage.json`，HTML 报告部署到 `report/coverage.html`
- README badge → `https://xenoamess.github.io/vivhite-tracker/coverage.json`

## 踩到的坑

1. **`org.json.JSONObject` 在本地单测中是 android.jar stub**：解析函数全部返回
   Error 分支，Live/NotLive 断言全挂。修复：`testImplementation("org.json:json:20260719")`
   （初版 20240303，随 Dependabot 升级）提供真实 JVM 实现，classpath 上优先于 android.jar stub。
2. 首次 `parseScriptContent` 的 `"status":1` 用例设计时注意正则 `[^"\s,}]+` 遇到 `}` 截断。

## 结果

- 总覆盖率 4%（red），api 包 36%。诚实信号：服务/广播/界面代码无 Robolectric 无法单测
- 首次部署两个 job 均 success，badge JSON/报告页/badge SVG 全部 200

## skill 反馈

Android Gradle 变体对本 skill 是新材料，已回写到 SKILL.md（Pitfall 12 + worked example）。

## 2026-07-24 追加：场景驱动测试补齐

- 总覆盖率 4% → 27%（domain 100% / util 83% / worker 41% / api 36% / receiver 18%）
- Robolectric 测试的覆盖率需要 `includeNoLocationClasses=true` + `excludes=["jdk.internal.*"]`
  （沙箱类加载器绕过 JaCoCo agent；不配的话 Robolectric 覆盖的类全部显示 0%）
- Robolectric 4.16.1 不支持 sdk=36，`app/src/test/resources/robolectric.properties` 锁 sdk=35
- instrumented test 发现一个覆盖率之外的真 bug（onCreate 无条件复活已停止的监控），
  证明"场景驱动 > 数值驱动"：service 包行覆盖率仍为 0%，但其决策已全部有回归保护

## 2026-07-24 追加（二）：P0-P3 场景补齐 27%→59%

- service 0%→84%（ServiceController 生命周期测试）、receiver 18%→80%、
  worker 41%→84%、api 36%→49%、总 27%→59%，单测 71 例
- 测试前提重构：LiveStatusChecker / ServiceStarter 两个注入接口
- Robolectric API 备忘（4.16）：ServiceController 在
  `org.robolectric.android.controller`；广播断言用
  `shadowOf(service).broadcastIntents`（ShadowContextWrapper），
  ShadowApplication 没有 broadcastsSent
- 慢 runner 竞态：连续两次 startCommand 会撞 isChecking 锁被跳过，
  测试用"重复触发模拟闹钟"规避（S9，CI 上挂过一次）
- 剩余未覆盖：root UI 层 8%（P4 未做）、api 网络体（checkByApi/WebPage 真网络）

## 2026-07-24 追加（三）：P4 UI 层补齐 59%→78%

- root 包 8%→76%（MainActivity/AlertActivity/LogActivity/LiveMonitorApp），总 59%→78%，单测 104 例
- Robolectric UI 测试三个坑：
  1. OnBackPressedCallback 绑定生命周期，activity 必须 setup() 到 RESUMED 才会拦截（create() 不够）
  2. MainActivity 的 tvLastCheck 在 onResume 刷新，测试同样要 setup() 而非 create()
  3. 运行时权限（POST_NOTIFICATIONS）Robolectric 默认未授权，走权限申请分支导致断言不到服务启动；
     需 shadowOf(app).grantPermissions(...) 显式授权
- 剩余未覆盖：api 网络体（真 HTTP）、triggerAlert 响铃/震动（系统行为，instrumented 覆盖）、
  AppLogger trim 边界边角

## 2026-08-04 追加（四）：AGP 9 迁移与当前状态

- **工具链迁移**：AGP 9.3.1 + 内置 Kotlin（无外部 KGP 插件），compileSdk/targetSdk 36，
  Gradle 9.6.1，JDK 17。JaCoCo `classDirectories` 必须指向内置 Kotlin 输出
  `intermediates/built_in_kotlinc/debug/compileDebugKotlin/classes`（旧 `tmp/kotlin-classes/debug`
  已不存在），否则报告 0%。`includeNoLocationClasses` + `excludes=["jdk.internal.*"]` 的配置保留。
- Robolectric 单测仍锁 sdk=35（`app/src/test/resources/robolectric.properties`）。
- **当前总覆盖率 86%**（INSTRUCTION），单测 452 例 / 38 个测试文件：
  - domain 98% / receiver 91% / util 89% / service 85% / worker 84% / api 72% / root(UI) 80%
- 覆盖对象相比 P4 阶段新增：活动监控（ActivityDecider / BilibiliActivityApi）、
  更新系统（UpdateDecider / ChainPlanner / IncrementalUpdater / AppUpdater）、
  魔法期（MagicPeriodDecider / MagicPeriodStore / MagicAlarmScheduler）、
  分享（ShareTextDecider / QqShare / QqGroups）等。
- 实测命令：`JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew jacocoUnitTestReport`（本机默认 GraalVM 会构建失败，见 AGENTS.md）。

## 2026-08-04 追加（五）：新功能期（勿扰/场次/Widget/统计/回放/预告/主题）

- **总覆盖率保持 86%**（INSTRUCTION），单测 **485 例 / 45 文件**（新增 8 文件、约 33 例）。
- 新增包：`controller` 83%（ShareController）、`db` 77%（Room DAO/实体）、`widget` 27%（LiveStatusWidgetProvider，
  Robolectric 只覆盖纯渲染决策 `buildStatus`，RemoteViews 组装留给真机）。
- 既有包上升：api 72%→81%（LIVE_RCMD 解析）、receiver 91%→93%、service 85%→87%。
- 新覆盖对象：QuietHoursDecider / StreamStats / LiveReminderDecider / LiveStatusWidgetProvider.buildStatus /
  StreamSessionDao（Room in-memory）/ StatsActivity / 服务状态机 S15-S17 + A5-A8（勿扰静音/场次闭合/回放/预告/动态类型过滤/标题变化）。
- 新 prefs 默认值/round-trip 并入 PreferenceManagerTest。
- 覆盖缺口（真机/系统行为，非数值能补）：Widget RemoteViews 渲染、日历点选交互、Room 生产库迁移、LIVE_RCMD 真实字段形态。
