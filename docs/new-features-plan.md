# 新功能执行计划

> 日期：2026-08-04
> 用户决策：① 勿扰时段内静音通知，默认关闭、设置手动开启 ② 场次存储上数据库（Room）③ 统计页独立 Activity ④ Phase 4 全部要做。

## 技术前置：引入 Room 数据库

**方案**：Room 2.8.4 + KSP 2.3.11（与 AGP 9.3.1 内置 Kotlin 2.3.20 匹配）。
已联网核实 AGP 9 内置 Kotlin 下 KSP 可用（google/ksp 有针对性修复）。

**集成**：
- `app/build.gradle.kts`：`id("com.google.devtools.ksp") version "2.3.11"` + `androidx.room:room-runtime`/`room-ktx`(2.8.4) + `ksp(androidx.room:room-compiler:2.8.4)`
- 新建 `db/` 包：`AppDatabase` / `StreamSessionDao` / `StreamSessionEntity` / `StreamTitleChangeEntity`

**风险与规避**：
- KSP 版本必须钉死匹配内置 Kotlin；实现时先做最小编译验证
- 已知 bug（google/ksp#3053）：Room + `@Parcelize` 实体在 AGP 9 下 KSP 解析失败 → 实体不用 `@Parcelize`
- 兜底：若 KSP 集成撞墙，退到 `SQLiteOpenHelper`（schema 就 2 张表，零注解处理）——功能设计不变，只换存储实现

**表结构（v1）**：
```
stream_sessions(id PK auto, start_ts, end_ts nullable, title)
stream_title_changes(id PK auto, session_id FK, changed_at, old_title, new_title)
```

## Phase 1 — 直播生命周期闭环

### 1.1 勿扰时段（默认关闭，静音通知）
- prefs：`quiet_hours_enabled`(false)、`quiet_start_minutes`(默认 23:00)、`quiet_end_minutes`(默认 07:00)
- `domain/QuietHoursDecider`（纯函数，处理跨午夜）
- `LiveCheckService.triggerAlert` / `triggerActivityAlert`：勿扰内 → 不响铃/不震动/不全屏，只发静音通知；标记 `wasQuiet` 供日志
- 设置抽屉新增「勿扰时段」section（开关默认关 + 起止时间选择器）
- 测试：`QuietHoursDeciderTest` + `LiveCheckServiceTest`

### 1.2 下播提醒
- prefs：`notify_stream_end`(true)
- `handleResult` Live→NotLive 且本场曾直播 → 通知「白绮下播了（时长）」；首检 NotLive 不误发
- 测试：`LiveCheckServiceTest`

### 1.3 场次记录（Room）
- 开播跳变：insert `stream_sessions(start_ts, end_ts=null)`
- 下播跳变：update `end_ts=now`、`title`
- 进程死亡恢复：onCreate 检测 `lastCheckLive=true` 且有未闭合场次 → 下播时按 `start=lastLiveStartTime` 补闭合
- 测试：`StreamSessionDaoTest`（Room in-memory）+ `LiveCheckServiceTest`

## Phase 2 — 可见性

### 2.1 桌面 Widget
- `widget/LiveStatusWidgetProvider` + `widget_live_status.xml` + `res/xml/live_status_widget_info.xml`
- 渲染「直播中(标题) / 未开播 / 已停止监控」，点击进直播间/主界面
- Manifest receiver + `APPWIDGET_UPDATE`；`handleResult`/`onCreate`/`onDestroy` 主动刷新
- 测试：Robolectric `ShadowAppWidgetManager`

### 2.2 场次统计页（独立 `StatsActivity`）
- 最近 N 场列表（日期/时长/标题）+ 本周/本月次数、平均/最长时长
- 聚合逻辑 `domain/StreamStats`（纯函数）；UI 薄壳
- 主界面「场次记录」入口

## Phase 3 — 开播预告

### 3.1 回放上线提醒 —— **已移除（无法准确识别回放）**

> 2026-08-05 用户反馈该功能无法准确识别回放（下播窗口内的新视频不一定是本场回放），已回退为普通新视频提醒。

### 3.2 开播预告（LIVE_RCMD）⚠️ 前置调研
- 先抓真实 desktop feed 确认 `LIVE_RCMD` 字段形态（预告开播时间/标题）
- `BilibiliActivityApi` 扩展解析 → `domain/LiveReminderDecider`（预告 ∈ (now, now+24h] 且按 start 去重 → 提醒一次）→ 可选 `AlarmScheduler` 提前 10min 精确闹钟
- 测试：解析 + 去重；**调研不过则本项降级为不接 LIVE_RCMD，不阻塞其他功能**

## Phase 4 — 增强（全做）

### 4.1 直播主题变化提醒
- 60s 轮询 Live 时取标题（复用 `BilibiliApi.parseRoomTitle`），变化且开播>5min → 通知（prefs `notify_title_change` 默认关）+ 写 `stream_title_changes`
- 测试：标题变化判定

### 4.2 动态类型过滤
- 活动监控设置加「动态类型」多选（图文/转发/专栏），`handleDynamicResult` 只提醒勾选类型
- prefs：`monitor_dynamic_types`（Set 默认全开）
- 测试：过滤

### 4.3 深色主题
- 新增 `Theme.BilibiliLiveMonitor.Dark` + 设置项（prefs `dark_mode`：跟随系统/浅色/深色）
- Manifest + MainActivity 应用；测试：选择落 prefs

## 横切关注点
- 新 prefs 键全进 `PreferenceManager`
- 纯决策逻辑一律 `domain/` + 单测
- 通知通道收敛：下播/预告合并新 `stream_lifecycle`(MED) 通道；勿扰不新建通道
- 每期结束 `lintDebug + testDebugUnitTest + assembleDebug`，可打 `v*` tag

## 工作量估算
| 期 | 内容 | 估 |
|---|---|---|
| 前置 | Room+KSP 集成（含风险验证） | 0.5 天 |
| P1 | 勿扰+下播+场次记录 | 2 天 |
| P2 | Widget+StatsActivity | 2 天 |
| P3 | 开播预告（含调研） | 1 天 |
| P4 | 主题/过滤/深色 | 1.5 天 |

## 执行状态（2026-08-04 全部落地）

| 项 | 状态 | 要点 |
|---|---|---|
| 前置 Room+KSP | ✅ | KSP 2.3.11 + Room 2.8.4（AGP9 内置 Kotlin 验证通过），表 v1 |
| P1.1 勿扰时段 | ✅ | 默认关/静音通知；`QuietHoursDecider` + 抽屉「勿扰时段」 |
| P1.2 下播提醒 | ✅ | `notify_stream_end`(默认开)，含时长；抽屉「直播提醒」开关 |
| P1.3 场次记录 | ✅ | Room 开→下 闭合 + 进程死亡补闭合；S16 |
| P2.1 桌面 Widget | ✅ | `LiveStatusWidgetProvider` 直播中/未开播/已停止 + 一键进直播间；handleResult 刷新 |
| P2.2 StatsActivity | ✅ | 最近 50 场 + 周/月/平均/最长；主界面「场次」入口 |
| P3.1 回放上线提醒 | ❌ 已移除 | 无法准确识别回放（2026-08-05） |
| P3.2 开播预告 | ✅ | LIVE_RCMD 防御式解析（字段形态待有预约直播时实测）；24h 窗口 + 去重 |
| P4.1 主题变化提醒 | ✅ | Live 带 title；变化且开播>5min 提醒（默认关）+ 记 DB |
| P4.2 动态类型过滤 | ✅ | `monitor_dynamic_types`（图文/转发/专栏 多选） |
| P4.3 深色主题 | ✅ | DayNight + `dark_mode`（跟随系统/浅/深） |

**遗留验证项**：P3.2 LIVE_RCMD 的真实字段形态需等白绮有预约直播时在 beta 实测确认（防御式解析取不到会安全降级不提醒，不破坏功能）。
