# 项目下一步优化与补全计划

> 日期：2026-08-05
> 范围：不含任何安全相关整改。
> 现状基准：v1.9.0 已发布（versionCode 217）；工作区干净；CI（build/android-test/Pages）全绿；单测 485 例 / 总覆盖率 86%。

## 现状要点（核实过的事实，作为计划依据）

- `LiveCheckService.kt` 1259 行、`MainActivity.kt` 1723 行、`MainActivityTest.kt` 2273 行——大型类集中度仍高。
- **场次标题从未落库**：`recordStreamStart/End` 只存 startTs/endTs，title 恒 null → StatsActivity 每场都显示「（无标题）」。
- Widget `updatePeriodMillis=0`（仅服务状态变化时刷新），服务被杀后 Widget 状态陈旧。
- 开播预告（LIVE_RCMD）解析为防御式，真实字段形态未在有预约直播时实测。
- `stream_title_changes` 表已写入但无 UI 展示。
- lint：1 error（`REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` → BatteryLife，功能必需）+ 若干 KTX/Locale/overdraw 警告。
- Gradle 持续提示未启用 configuration cache。

## P0 — 补明显缺口（低风险，尽快做）

> 状态：✅ 已完成（2026-08-05，484 例单测 0 失败 / lint 0 error）

| # | 项 | 现状/问题 | 动作 | 估 |
|---|---|---|---|---|
| 1 | **场次标题落库** | `recordStreamStart/End` 只存时间，title 恒 null | `recordStreamEnd` 用 `preferenceManager.getLastLiveTitle()` 补 title；`recordStreamStart` 有 title 时直接带上 | 0.5h |
| 2 | **Widget 显示直播标题 + 定期刷新** | `updatePeriodMillis=0`，服务被杀后状态陈旧 | 标题进 Widget；`updatePeriodMillis=1800000`（30min）；`BootReceiver` 补 `updateAll` | 1h |
| 3 | **统计页空状态引导** | 无场次时只有提示文字+空列表 | 空态文案：首次使用引导（开播后自动记录 / 添加桌面小组件） | 0.5h |

## P1 — 功能补全（中价值）

> 状态：✅ 已完成 #5/#6/#7/#8（2026-08-05，commit `7b3f640`，487 例单测 0 失败 / lint 0 error）；#4 仍待真实样本

| # | 项 | 说明 | 动作 | 估 |
|---|---|---|---|---|
| 4 | **LIVE_RCMD 实测校准** | 预告解析是防御式，字段未验证 | 固定 `parseLiveRcmd` fixture + 测试（已有）；待白绮有预约直播时抓真实 feed 校准并替换 fixture | 待样本 |
| 5 | **勿扰「错过提醒」汇总** | 勿扰内开播只静音，醒来不知昨晚是否开播 | 勿扰窗口结束后若窗口内有开播且未被观播，补一条「昨晚 HH:MM 开播了」通知（prefs 记录窗口内开播时间） | 1.5h |
| 6 | **统计页增强** | 目前只有月历+当天列表 | 周/月开播柱状（简单 View 绘制）、星期偏好；月度切换已有 | 2h |
| 7 | **主题变化在统计页展示** | `stream_title_changes` 已记录无 UI | 选中场次展开列出该场标题变化时间线 | 1.5h |
| 8 | **场次导出** | 粉丝留存记录 | 统计页「导出」→ 文本/CSV 分享（复用 FileProvider） | 1h |

## P2 — 工程优化（长期收益）

> 状态：✅ #11/#13 已完成；#12 判定不启用（原因见下）。#9/#10 拆分专项留待单独排期。

| # | 项 | 现状 | 动作 | 估 |
|---|---|---|---|---|
| 9 | **LiveCheckService 拆分** | 1259 行（通知/场次/活动监控/提醒/排程集中） | 抽 `NotificationBuilder`、`StreamSessionTracker`（场次记录决策可单测），收敛 `recordStreamStart/End`+`trackTitleChange` | 1天 |
| 10 | **MainActivity 继续拆分** | 1723 行（ShareController 已抽 426 行） | `UpdateController`（更新检查/下载 ~200 行）、`MagicPeriodDialogFragment`（~261 行日历对话框） | 1天 |
| 11 | **lint 清零** | 1 error + 若干警告 | `tools:ignore` 豁免 BatteryLife（功能必需）；清 Locale.getDefault 与个别 KTX 提示 | 0.5天 |
| 12 | **configuration cache 启用** | 构建每轮 ~1.5min | 启用后跑全量验证（KSP/Room 兼容则保留） | 0.5天 |
| 13 | **测试补强** | Widget 27%、Room 无迁移测试、日历交互无专门测试 | Widget 渲染 instrumented、Room 空库/多场次边界、StatsActivity 日历点选测试 | 0.5天 |

### P2 落地记录（2026-08-05）

- **#11 ✅**：lint 0 error。`MainActivity.openBatterySettings` 加 `@SuppressLint("BatteryLife")`（请求电池白名单是本应用监控功能的必要用途）；新建 `WeekStreamBarsView` 的 Paint/RectF 提升为字段消除 DrawAllocation。
- **#12 ❌ 判定不启用**：`--configuration-cache` 实测 AGP 9.3.1 内部读取 Gradle 属性 `android.injected.build.model.only.advanced` 时无法序列化（trace 指向 `plugin 'com.android.internal.application'`），属 AGP 层限制、仓库脚本无法修复，按「KSP/Room 兼容则保留」准则不启用。**顺手保留的脚本卫生改进**：`generateChangelog` doLast 改 `ProcessBuilder`（不再捕获 `providers`/Project）、`writeVersionInfo` 配置期预计算目标 File、`assets.srcDir` → `directories`（AGP9 去弃用 API）。后续若 AGP 修复可重新评估。
- **#13 ✅**：`LiveStatusWidgetProvider.computeContent` 抽纯函数 + 4 组断言（直播展示标题/空标题隐藏/未开播隐藏/停止隐藏）；`StreamSessionDaoTest` 补 空库、多场次下 `closeOpenSessions` 只闭合残留、标题变化按 session 隔离且升序；`StatsActivityTest` 补 日历点选空日（清空列表+「无直播」+空态隐藏）、选中日主题变化时间线展示。487 → 493 例。

## 明确不做（维持原判）

- **PromoImageRenderer 拆分/参数化**（1681 行，行为即视觉）
- **多主播支持**（单主播硬编码是产品定位）
- **i18n / 多语言**（中文粉丝向）
- **回放识别**（已移除，无法准确识别）
- **version catalog**（已判定无价值）
- 安全相关（按用户要求全部排除）

## 执行顺序

```
P0（#1-3，半天）→ P1（#4-8，按需取舍）→ P2（#9-10 拆分专项，单独排期）→ P2 清理（#11-13）
```

- 每项独立提交 + 可发版，沿用 `lintDebug + testDebugUnitTest + assembleDebug` 验证。
- 拆分专项（#9/#10）沿用 ShareController 的「internal 委托保留、测试不迁移」策略。
