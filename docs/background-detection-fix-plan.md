# 后台检测失效修复计划

**日期**: 2026-07-23
**症状**: 不充电时、黑屏过久时、应用在后台时，主播开播但检测不到。

## 诊断结论

症状是典型的 **Doze 模式 + 国产 ROM 后台限制** 组合拳。按嫌疑从大到小:

### ① SCHEDULE_EXACT_ALARM 权限从未引导用户开启（最关键）
Manifest 声明了权限，代码里有 `canScheduleExactAlarms()` 兜底（LiveCheckService.kt:352），但 MainActivity 从未弹窗引导。Android 13+ 该权限默认不授予，静默走 `setAndAllowWhileIdle` 兜底路径，Doze 下被系统节流成 9~15 分钟一次。60 秒轮询 → 实际 15 分钟一次，开播就这么漏的。

### ② ForegroundServiceStartNotAllowedException 被静默吞掉
AlarmReceiver.kt:28、ServiceRestartReceiver.kt:25、BootReceiver.kt:22 都调 `startForegroundService`，Android 12+ 后台启动 FGS 会抛 `ForegroundServiceStartNotAllowedException`。现在 `catch (e: Exception)` 后只打一行 log，服务没起来，无任何降级。

### ③ 国产 ROM 的"自启动/后台管理"独立于电池优化白名单
目前只引导了 `ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS`（MainActivity.kt:220）。MIUI / ColorOS / FuntouchOS / EMUI 还有单独的自启动开关和后台运行限制，不在标准电池优化白名单里。

### ④ 网络错误和"未开播"混为一谈
BilibiliApi.kt:12：网络异常 / API 挂掉 / 真的没开播，全都返回 false。Doze 下网络不可达时把状态错误刷成 false，下次成功时会重复触发提醒；开播事件也可能被网络抖动吞掉。

### ⑤ 架构 100% 依赖 AlarmManager，没有 Plan B
一旦 AlarmManager 也被 OEM 压制（MIUI 几小时后常见），监控彻底停摆。缺一个 Doze 友好的兜底机制。

### ⑥ 服务没有重写 onTaskRemoved
部分 ROM 划掉最近任务卡片时，即使 `stopWithTask="false"` 也会杀服务。

## 修复方案

### 决策（用户已确认）
1. 轮询架构: **AlarmManager + WorkManager 双轨**（保留 60s 轮询，加 15min WorkManager 兜底）
2. 国产 ROM 引导: **做**，小米/OPPO/vivo/华为/荣耀各自深链跳转
3. 日志: **做**，本地文件日志 + 应用内查看页
4. API 返回值: **改三态** Live / NotLive / Error

### 新增文件（5）

| 文件 | 作用 |
|---|---|
| `worker/LiveCheckWorker.kt` | WorkManager 周期任务（15min + NetworkType.CONNECTED）+ 一次性降级任务 |
| `util/OemHelper.kt` | Build.MANUFACTURER 识别国产 ROM，构建自启动/后台管理页 intent，失败降级到应用详情页 |
| `util/AppLogger.kt` | 本地文件日志（filesDir/logs/monitor.log，超 1MB 截断前一半） |
| `LogActivity.kt` + `res/layout/activity_log.xml` | 日志查看页，支持全选复制 |

### 修改文件（9）

| 文件 | 改动 |
|---|---|
| `api/BilibiliApi.kt` | 返回 sealed class LiveStatus: Live / NotLive / Error(reason) |
| `service/LiveCheckService.kt` | 适配三态；Error 时不更新 lastStatus 并 15s 后重试；重写 onTaskRemoved 自拉起；关键路径写 AppLogger |
| `receiver/AlarmReceiver.kt` | 单独 catch ForegroundServiceStartNotAllowedException，降级为一次性 WorkManager |
| `receiver/ServiceRestartReceiver.kt` | 同上 |
| `receiver/BootReceiver.kt` | 同上 |
| `MainActivity.kt` | ① SCHEDULE_EXACT_ALARM 引导（启动检查 + onResume 复查）② 国产 ROM OEM 引导弹窗 ③ "查看日志"入口 ④ 显示上次检测时间+结果 |
| `LiveMonitorApp.kt` | 注册 WorkManager 唯一周期任务（enqueueUniquePeriodicWork + KEEP） |
| `util/PreferenceManager.kt` | 存上次检测时间/结果，UI 与 Worker/Service 共享 |
| `AndroidManifest.xml` | 注册 LogActivity |

依赖: work-runtime-ktx 2.9.0 已在 app/build.gradle.kts:76，无需新增。

## 现实约束（平台限制，无法绕过）

1. **Doze 下 60 秒轮询是平台级限制**。双轨之后: 亮屏/充电/前台仍是 60 秒；黑屏 Doze 中实际约 15 分钟一次（系统保证）。要更快只能靠电池优化白名单 + 自启动权限，所以 ROM 引导是关键一环。
2. **国产 ROM 引导无法全自动**，深链只能把用户带到设置页，最后一步开关需用户手动点。

## 执行顺序

1. 地基: AppLogger → BilibiliApi 三态 → PreferenceManager
2. 核心修复: LiveCheckService + 3 个 Receiver
3. 双轨: LiveCheckWorker + LiveMonitorApp 注册
4. 引导与观测: OemHelper + MainActivity + LogActivity
5. 验证: `./gradlew lintDebug assembleDebug`

## 不在本次范围

- 远程日志上传（Crashlytics/Sentry）
- 开播提醒展示逻辑（AlertActivity 不动）
