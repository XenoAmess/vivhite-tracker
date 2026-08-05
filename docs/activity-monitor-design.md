# B 站全活动监控设计

## 背景

应用在直播开播监控之外，扩展监控白绮在 B 站的所有公开活动：新视频投稿、置顶/代表作变化、动态（图文/专栏/转发/直播预告）。

**现状（2026-08）**：已落地。三个活动功能（视频 / 动态 / 置顶）统一由**一个**未登录可用的桌面端动态流端点驱动，不再需要 wbi 签名、buvid3 或登录态（早期方案的 wbi/风控管线已废弃，见「调研结论」）。

## 调研结论（2026-07 实测，含废弃方案）

### B 站 API 能力矩阵

| 活动类型 | 接口 | 未登录 | 结论 |
|---|---|---|---|
| 直播开播状态 | `/room/v1/Room/get_info` | ✅ | ✅ 已实现 |
| **视频/动态/置顶（合一）** | `/x/polymer/web-dynamic/desktop/v1/feed/space` | ✅（实测） | ✅ **当前采用** |
| 投稿视频列表 | `/x/space/wbi/arc/search` | 需 wbi + dm_img | ❌ 已废弃 |
| 动态流（移动端） | `/x/polymer/web-dynamic/v1/feed/space` | 需 buvid3 + wbi + dm_img，HTTP 412 | ❌ 已废弃 |
| 置顶/代表作 | `/x/space/top/arc`、`/x/space/masterpiece` | ✅ | 已被桌面端 feed 的 `is_top`/`module_author` 覆盖 |
| 专栏列表 | `/x/space/article/list` | 已废弃 | ❌ 走动态流 |

**关键发现**：桌面端 `feed/space` 对未登录完全开放，一次请求同时返回：
- 投稿视频（DYNAMIC_TYPE_AV / DYNAMIC_TYPE_ARCHIVE，含 aid/bvid/title/cover/play/like）
- 图文动态（DYNAMIC_TYPE_DRAW）、转发（DYNAMIC_TYPE_FORWARD）、专栏（DYNAMIC_TYPE_ARTICLE）
- 置顶标记（`module_author.is_top`）

**废弃原因**：`wbi`/`buvid3`/`dm_img` 是登录/风控套件，未登录高频请求易被 -352/-412 风控；桌面端点免登录无此负担。原 `SocialSisterYi/bilibili-API-collect` 仓库已因律师函关停，App 内不内嵌 API 文档，只实现调用。

## 硬编码常量

```kotlin
// BilibiliActivityApi 伴生对象
const val MONITOR_MID = 251990176L     // 白绮的 B 站 UID
// 直播房间号 MONITOR_ROOM_ID = 11258892L 在 QqShare / LiveCheckService 等多处
```

与房间号同策略：硬编码多处，改 mid 要全改。

## 架构

```
LiveCheckService（60s 直播检查，不动）
   └─ 动态流独立 5min Alarm（±10s 抖动）→ ACTION_CHECK_DYNAMICS
        └─ checkNewDynamics()
             ├─ 前置：prefs.serviceRunning && 任一活动开关开启
             ├─ fetchDynamicOnce() → BilibiliActivityApi.fetchLatestDynamic(MID)
             │    └─ 桌面端 feed/space（未登录，无 wbi/buvid3）
             ├─ Err/NoData → 等 15s 重试一次（对齐直播检测策略）
             └─ handleDynamicResult(info)
                  ├─ ① 视频基线：latestAvItem（置顶视频兜底）→ shouldAlertVideo → 视频提醒
                  ├─ ② 动态基线：首条非置顶动态 id → shouldAlertDynamic → 动态提醒
                  └─ ③ 置顶变化：pinnedAvItem → shouldAlertPinned → 置顶提醒
                        └─ triggerActivityAlert(type)
                             ├─ 通知（必发，点击跳对应页面）
                             └─ 响铃（仅当 prefs.alert_ring_on_activity）
```

三个活动功能共用一次 feed 请求统一处理，而不是随直播检查每分钟打接口（防风控 + 省流量）。动态 Alarm 只在 `ACTION_CHECK_DYNAMICS` 完成后重排，避免 60s 直播检查把触发时间不断往后推。

## 为什么动态流单独 5min

- 60s 轮询该接口偏激进，高频未登录请求易触发风控。
- 5min + ±10s 随机抖动（`Math.random()*20s - 10s`）降低被识别为机器人的概率。
- 动态延迟 5min 可接受（不像开播需要秒级响应）。

## 数据模型（PreferenceManager，共 7 键）

| 键 | 类型 | 默认 | 用途 |
|---|---|---|---|
| `monitor_videos` | Boolean | **true** | 监控新视频开关 |
| `monitor_pinned` | Boolean | **true** | 监控置顶变化开关 |
| `monitor_dynamics` | Boolean | **true** | 监控动态开关 |
| `monitor_dynamic_types` | Set\<String\> | 图文/转发/专栏 | 动态类型过滤（勾选的类型才提醒） |
| `alert_ring_on_activity` | Boolean | **true** | 新视频/动态时是否响铃（开播不受此控制） |
| `last_video_aid` | Long | -1 | 上次见到的最新视频 avid |
| `last_pinned_aid` | Long | -1 | 上次见到的置顶视频 avid |
| `last_dynamic_id` | String | "" | 上次见到的最新动态 id |

> 早期方案规划的 wbi_img_key / wbi_sub_key / wbi_key_updated_at / buvid3 4 个键，因桌面端点无需 wbi 已不落地。

## 相关文件

| 文件 | 职责 |
|---|---|
| `api/BilibiliActivityApi.kt` | 桌面端 feed/space 拉取 + 解析（DynamicInfo 含 pinnedAvItem/latestAvItem/liveRcmd） |
| `domain/ActivityDecider.kt` | 纯函数：跳变检测 + 首次不提 |
| `domain/LiveReminderDecider.kt` | 纯函数：开播预告（LIVE_RCMD）24h 窗口 + 去重 |
| `service/LiveCheckService.kt` | 5min Alarm + checkNewDynamics + handleDynamicResult + triggerActivityAlert |
| `res/layout/expand_section_activity.xml` | 主界面「活动监控」折叠区（3 开关 + 动态类型复选框 + 响铃开关） |
| `LiveMonitorApp.kt` | 通知通道 `video_alert_v2` / `dynamic_alert_v2` / `stream_lifecycle` |

## BilibiliActivityApi

`fetchLatestDynamic(mid)` → `ActivityResult<DynamicInfo>`（Ok / NoData / Err）。

- 端点：`/x/polymer/web-dynamic/desktop/v1/feed/space?host_mid=$mid&features=itemOpusStyle`。
- `DynamicInfo`：`id`（去重）、`type`、`displayText`、`avItem`、`isTop`、`pubTs`，
  另带 **`pinnedAvItem`**（当前置顶视频）、**`latestAvItem`**（feed 中最新非置顶视频）与
  **`liveRcmd`**（本页直播开播/预告条目）三个独立字段——
  最新动态为图文时，视频监控仍能正确推进投稿基线；置顶视频单独保留，避免被最新非置顶动态掩盖。
- 解析兼容 `modules` 的 JSONObject / JSONArray 两种形态；`module_desc.text` 直挂与嵌套 `desc.text` 都兼容。
- `parseDynamicFeed` 跳过置顶项（items[0] 恒为置顶），正常返回最新非置顶项；全是置顶时回退该项落基线。
- `DYNAMIC_TYPE_LIVE_RCMD` 只参与开播预告提取，**不计入动态基线**（避免触发"新动态"误报）。

## 动态类型过滤

`monitor_dynamic_types`（Set）控制哪些动态类型提醒：图文（DRAW）/ 转发（FORWARD）/ 专栏（ARTICLE），
默认全开。`handleDynamicResult` 只对勾选类型触发提醒；基线（last_dynamic_id）始终推进，不受过滤影响。
UI 在「活动监控」折叠区以 3 个复选框呈现。

## 开播预告提醒（LIVE_RCMD）

- `BilibiliActivityApi.parseLiveRcmd` 解析 `DYNAMIC_TYPE_LIVE_RCMD` → `LiveRcmdInfo`（dynamicId / liveStartMs / title / contentText）。
  `live_start_time` 兼容毫秒/秒级时间戳；字段形态随 B 站可能变化，取不到时**防御式降级不提醒**（需在有预约直播时实测校准）。
- `domain/LiveReminderDecider.shouldRemind`：预告时间在 `(now, now+24h]` 且按动态 id 去重 → 提醒一次。
- 通知走 `stream_lifecycle`（MED）通道：「白绮直播预告」+ 预计开播时间。
- 下播提醒（`notify_stream_end`）、直播中标题变化（`notify_title_change`，默认关）同用
  `stream_lifecycle` 通道，见 `docs/new-features-plan.md`。
  （注：曾尝试「回放上线提醒」（下播 6h 窗口内新视频标回放），无法准确识别回放，2026-08-05 已移除。）

## ActivityDecider（纯函数）

```kotlin
sealed class ActivityType {
    object Live : ActivityType()
    data class Video(val aid: Long, val title: String) : ActivityType()
    data class Pinned(val aid: Long, val title: String) : ActivityType()
    data class Dynamic(val id: String, val displayText: String) : ActivityType()
}

// 首次启动策略：不提（只记录当前最新 id），避免装完就响一片
fun shouldAlertVideo(newAid: Long, lastAid: Long?): Boolean = lastAid != null && newAid != lastAid
fun shouldAlertPinned(newAid: Long?, lastAid: Long?): Boolean = lastAid != null && newAid != lastAid
fun shouldAlertDynamic(newId: String, lastId: String?): Boolean = lastId != null && newId != lastId
fun longToNullable(value: Long): Long?     // -1 → null
fun stringToNullable(value: String): String? // 空串 → null
```

**关键设计：首次不提醒**——App 新装/升级后第一次检测只记录当前最新 id，不触发提醒。否则用户装完瞬间收到"新视频"通知（实际是历史视频）。`Long?`/`String?` 让"未初始化"与"有效值"语义清晰。

## 提醒分级

| 活动类型 | 通知通道 | 优先级 | 响铃 | 点击跳转 |
|---|---|---|---|---|
| 开播（现有） | `live_alert` (HIGH) | 高 | ✅ 默认 | AlertActivity → 直播间 |
| 新视频 | `video_alert_v2` (HIGH) | 高 | ⚙️ 可选（默认开） | `https://www.bilibili.com/video/av{aid}` |
| 置顶变化 | `video_alert_v2` (HIGH) | 高 | ⚙️ 可选 | 跳新置顶视频 /「白绮置顶已取消」纯文本 |
| 新动态 | `dynamic_alert_v2` (HIGH) | 高 | ⚙️ 可选（默认开） | `https://t.bilibili.com/{id}` |

要点：
- **通道升 HIGH**：旧 `video_alert`/`dynamic_alert` 是 DEFAULT/LOW，被系统折叠无横幅（2026-08 用户反馈），
  channel 重要性被系统记住后不可改，只能换新 id（`video_alert_v2`/`dynamic_alert_v2`），旧 id 在
  LiveMonitorApp 启动时删除。
- **点击跳转用官方 web 链接 + `setPackage` 强投递**到已装 B 站客户端；`bilibili://dynamic/{id}` 无路由、
  `bilibili://dynamic/detail/{id}` 真机解析为空，均已废弃。未装客户端则浏览器兜底。
- 响铃复用 AlertSoundProvider（与开播提醒共享铃声源），`alert_ring_on_activity=true` 时 `playAlertSound()`。

## UI

主界面设置抽屉「活动监控」折叠区（`expand_section_activity.xml`）：

```
┌─ 活动监控 ────────────────────┐
│  ☑ 监控新视频投稿             │
│  ☑ 监控置顶视频变化           │
│  ☑ 监控动态                   │
│  ─────────────────            │
│  动态类型（勾选的才提醒）      │
│  ☑ 图文  ☑ 转发  ☑ 专栏        │
│  ─────────────────            │
│  ☑ 新视频/动态时也响铃         │
│  ☑ 监控动态                   │
│                               │
│  ─────────────────            │
│                               │
│  ☑ 新视频/动态时也响铃         │
│    （开播始终响铃，不受此控制） │
└───────────────────────────────┘
```

## 容错与已知坑

- 桌面端点间歇性返回 `code=0` 但 `items=[]`（2026-08-02 实测约 1/6 抽风率）：Err/NoData 统一等 15s 重试一次。
- `activityCheckMutex` 防并发：上次检测未完成时跳过本次，避免重入。
- 检测失败静默不扰 + AppLogger 记录，不阻塞直播监控。

## 测试

- `domain/ActivityDeciderTest.kt`：跳变检测、首次不提、各类型决策、null 转换。
- `api/BilibiliActivityApiTest.kt`：桌面端 feed 解析（AV/DRAW/FORWARD/ARTICLE、置顶项跳过、两种 modules 形态）、错误处理。
- `service/LiveCheckServiceTest.kt`：ACTION_CHECK_DYNAMICS 编排、15s 重试、三路提醒触发与去重、开关关闭时不调 API。
- `api/BilibiliApiOrchestrationTest.kt` 等其他既有测试不破。
