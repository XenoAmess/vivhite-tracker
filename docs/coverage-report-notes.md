# Coverage Report Notes — XenoAmess/vivhite-tracker

**日期**: 2026-07-23

## 项目形态

- Android 单模块 Kotlin App（AGP 8.5.0, Kotlin 2.0.0, JDK 17），**不是 Maven，不是 Vitest**
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
    `tmp/kotlin-classes/debug`（Kotlin！不是 java），排除 R/BuildConfig/databinding
- CI（android-ci.yml）：`jacocoUnitTestReport` → 上传 `coverage-report-${{ github.sha }}` →
  `coverage-pages` job（`if: master push`，`needs: [build]`）解析 jacoco.xml 的
  INSTRUCTION counter 生成 `coverage.json`，HTML 报告部署到 `report/coverage.html`
- README badge → `https://xenoamess.github.io/vivhite-tracker/coverage.json`

## 踩到的坑

1. **`org.json.JSONObject` 在本地单测中是 android.jar stub**：解析函数全部返回
   Error 分支，Live/NotLive 断言全挂。修复：`testImplementation("org.json:json:20240303")`
   提供真实 JVM 实现，classpath 上优先于 android.jar stub。
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
