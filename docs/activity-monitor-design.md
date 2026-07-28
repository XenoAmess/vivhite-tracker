# B 站全活动监控设计方案

## 背景

当前 App 只监控直播开播状态（`room/v1/Room/get_info`，单一布尔值）。本方案将其扩展为监控白绮在 B 站的所有公开活动：新视频投稿、置顶/代表作变化、动态（图文/专栏/转发/直播预告）。

## 调研结论（2026-07）

### B 站 API 能力矩阵

| 活动类型 | 接口 | 未登录+无wbi | 必须 wbi | 必须 cookie/login | 可行性 |
|---|---|---|---|---|---|
| 直播开播状态 | `/room/v1/Room/get_info` | ✅ | | | ✅ 已实现 |
| 投稿视频列表 | `/x/space/wbi/arc/search` | | ✅ | | ✅ 推荐首选 |
| 置顶视频 | `/x/space/top/arc` | ✅ | | | ✅ 低成本补充 |
| 代表作视频 | `/x/space/masterpiece` | ✅ | | | ✅ 低成本补充 |
| 动态流 | `/x/polymer/web-dynamic/v1/feed/space` | | ✅ | 需 buvid3 + dm_img 风控套件（未登录）；登录则 SESSDATA | ⚠️ 脆弱 |
| 专栏列表 | `/x/space/article/list` | ❌ 已废弃 | | | ❌ 走动态流 |
| 评论监控 | 无"按 mid 查评论"接口 | | | | ❌ 放弃 |

### 动态流接口的动态类型

`feed/space` 返回的动态类型包括：`AV`（投稿视频）、`WORD`（纯文字）、`DRAW`（带图）、`ARTICLE`（专栏）、`OPUS`（图文新形态）、`FORWARD`（转发）、`LIVE_RCMD`（直播开播/预告）、`MUSIC`、`UGC_SEASON` 等。一条接口可覆盖视频/专栏/图文/直播预告，但风控"有运气成分"。

### wbi 签名

- 算法：① GET `/x/web-interface/nav` 拿 `img_key`/`sub_key`（全站统一，每日更替）② 用固定 64 长 `MIXIN_KEY_ENC_TAB` 置换表对 `img_key+sub_key` 重排取前 32 字符 → `mixin_key` ③ 参数加 `wts`=当前秒级时间戳，按 key 升序拼接为 query（百分号编码、大写、空格 `%20`、过滤 `!'()*`），末尾拼 `mixin_key`，MD5 取 hex → `w_rid`
- 实现量：约 80-100 行 Kotlin，无第三方依赖（`java.security.MessageDigest` + `java.net.URLEncoder`）
- key 缓存：每日更替，存 prefs，12 小时刷新一次，nav 接口挂了则降级

### 合规风险

原 `SocialSisterYi/bilibili-API-collect` 仓库已于 2026-01-28 因 B 站律师函永久关停。App 内**不内嵌 API 文档或接口列表**，只实现签名 + 调用。

## 硬编码常量

```kotlin
const val MONITOR_MID = 251990176L     // 白绮的 B 站 UID（从 room_id=11258892 的 get_info.uid 查得）
const val MONITOR_ROOM_ID = 11258892L  // 现有
```

与房间号同策略：硬编码多处，改 mid 要全改。

## 架构

```
LiveCheckService 60s 周期（直播 + 视频 + 置顶）
  ├─ checkLiveStatus()              [现有，不动]
  ├─ if (prefs.monitorVideos)
  │    checkNewVideos()              [新]
  │     ├─ WbiSigner.sign → space/wbi/arc/search
  │     ├─ 取列表第一个 avid
  │     └─ ActivityDecider.shouldAlertVideo(newAid, lastAid)
  ├─ if (prefs.monitorPinned)
  │    checkPinnedVideo()            [新]
  │     ├─ GET space/top/arc（无需 wbi）
  │     └─ ActivityDecider.shouldAlertPinned(newAid, lastAid)
  └─ triggerActivityAlert(type)
       ├─ 通知（必发，点击跳对应页面）
       └─ 响铃（仅当 prefs.alertRingOnActivity）

独立 5min Alarm（动态流，风控脆弱，降频 + ±10s 抖动）
  └─ if (prefs.monitorDynamics)
       checkNewDynamics()            [新，实验]
        ├─ buvid3 cookie + WbiSigner.sign → feed/space
        ├─ 取第一条动态 id
        └─ ActivityDecider.shouldAlertDynamic(newId, lastId)
```

### 为什么动态流单独 5min

- 60s 轮询该接口偏激进，高频未登录请求来自同 IP 会触发 412/-352 风控
- 5min + ±10s 随机抖动降低被识别为机器人的概率
- 动态延迟 5min 可接受（不像开播需要秒级响应）

## 数据模型（PreferenceManager 新增 8 键）

| 键 | 类型 | 默认 | 用途 |
|---|---|---|---|
| `monitor_videos` | Boolean | false | 监控新视频开关 |
| `monitor_pinned` | Boolean | false | 监控置顶变化开关 |
| `monitor_dynamics` | Boolean | false | 监控动态开关（实验） |
| `alert_ring_on_activity` | Boolean | false | 新视频/动态时是否响铃（开播不受此控制） |
| `last_video_aid` | Long | -1 | 上次见到的最新视频 avid |
| `last_pinned_aid` | Long | -1 | 上次见到的置顶视频 avid |
| `last_dynamic_id` | String | "" | 上次见到的最新动态 id |
| `wbi_img_key` | String | "" | wbi 签名 key 缓存 |
| `wbi_sub_key` | String | "" | wbi 签名 key 缓存 |
| `wbi_key_updated_at` | Long | 0 | wbi key 更新时间（12h 刷新） |
| `buvid3` | String | "" | 动态流接口需要的 cookie |

## 新增文件

| 文件 | 职责 | 行数估 |
|---|---|---|
| `util/WbiSigner.kt` | wbi 签名 + key 缓存（每日更替，存 prefs） | ~100 |
| `api/BilibiliActivityApi.kt` | 三源统一接口：视频列表 / 置顶视频 / 动态流 | ~200 |
| `domain/ActivityDecider.kt` | 纯函数：比对 last_aid/last_dynamic_id 跳变 + 提醒决策 | ~60 |
| `res/layout/dialog_activity_settings.xml` | 活动监控设置对话框 | ~50 |

## 改动文件

| 文件 | 改动 |
|---|---|
| `PreferenceManager.kt` | +11 键 |
| `LiveCheckService.kt` | 60s 周期加 `checkNewVideos()` + `checkPinnedVideo()`；新增独立 5min Alarm 调 `checkNewDynamics()` |
| `LiveMonitorApp.kt` | 新通知通道 `video_alert`（MED）、`dynamic_alert`（LOW） |
| `MainActivity.kt` | 新增「活动监控」按钮 + 设置对话框 + wbi key 初始化 + buvid3 获取 |
| `activity_main.xml` | 新增「活动监控」按钮 |
| `receiver/AlarmReceiver.kt` | 支持动态流 5min 独立 Alarm 的 action 区分 |

## wbi 签名实现要点

```kotlin
object WbiSigner {
    private val MIXIN_KEY_ENC_TAB = intArrayOf(
        46,47,18,2,53,8,23,32,15,50,10,31,58,3,45,35,
        27,43,22,51,55,33,30,5,54,37,11,40,28,19,38,10,
        // ... 共 64 个
    )

    // 1. 拿 key（缓存到 prefs，12h 刷新）
    suspend fun refreshKeysIfNeeded(prefs): Boolean

    // 2. 签名
    fun sign(params: Map<String, String>, imgKey: String, subKey: String): Map<String, String> {
        val mixinKey = getMixinKey(imgKey + subKey)  // 置换取前32
        val wts = (System.currentTimeMillis() / 1000).toString()
        val signed = params + ("wts" to wts)
        // 按 key 升序拼接，百分号编码（大写、空格%20、过滤!'()*）
        // 末尾拼 mixinKey，MD5 取 hex → w_rid
        return signed + ("w_rid" to wrid)
    }
}
```

## buvid3 cookie 获取（动态流专用）

首次启动时访问 `https://www.bilibili.com/` 首页，从 Set-Cookie 头提取 `buvid3`。存 prefs，失效时重新获取。视频列表接口不需要 buvid3。

## ActivityDecider（纯函数）

```kotlin
object ActivityDecider {
    sealed class ActivityType {
        object Live : ActivityType()
        data class Video(val aid: Long) : ActivityType()
        data class Pinned(val aid: Long) : ActivityType()
        data class Dynamic(val id: String, val displayText: String) : ActivityType()
    }

    // 首次启动策略：不提（只记录当前最新 id），避免装完就响一片
    fun shouldAlertVideo(newAid: Long, lastAid: Long?): Boolean =
        lastAid != null && newAid != lastAid

    fun shouldAlertPinned(newAid: Long?, lastAid: Long?): Boolean =
        lastAid != null && newAid != lastAid

    fun shouldAlertDynamic(newId: String, lastId: String?): Boolean =
        lastId != null && newId != lastId
}
```

**关键设计：首次不提醒**——App 新装/升级后第一次检测只记录当前最新 id，不触发提醒。否则用户装完瞬间收到"新视频"通知（实际是历史视频）。

## 提醒分级

| 活动类型 | 通知通道 | 优先级 | 响铃 | 点击跳转 |
|---|---|---|---|---|
| 开播（现有） | `live_alert` (HIGH) | 高 | ✅ 默认 | AlertActivity → 直播间 |
| 新视频 | `video_alert` (MED) | 中 | ⚙️ 可选（默认关） | `bilibili://video/{avid}` |
| 置顶变化 | `video_alert` (MED) | 中 | ⚙️ 可选 | 跳新置顶视频 |
| 新动态 | `dynamic_alert` (LOW) | 低 | ⚙️ 可选（默认关） | `bilibili://dynamic/{dynamic_id}` |

响铃复用 AlertSoundProvider（与开播提醒共享铃声源）。`alert_ring_on_activity=true` 时调 `playAlertSound()`，否则只发通知。

## UI 设计

MainActivity 新增「活动监控」按钮 → 弹设置对话框：

```
┌─ 活动监控设置 ────────────────┐
│                               │
│  ☑ 监控新视频投稿             │
│  ☐ 监控置顶视频变化           │
│  ☐ 监控动态（实验，不稳定）    │
│                               │
│  ─────────────────            │
│                               │
│  ☐ 新视频/动态时也响铃         │
│    （开播始终响铃，不受此控制） │
│                               │
│         [完成]                │
└───────────────────────────────┘
```

## 分阶段实施

| 阶段 | 内容 | 人日 |
|---|---|---|
| **阶段 1** | wbi 签名 + 新视频提醒（API + Decider + Service + 通知 + UI） | 3-4 |
| **阶段 2** | 置顶/代表作变化（无 wbi，低成本） | 0.5 |
| **阶段 3** | 动态流（buvid3 + dm_img + wbi + 5min 独立 Alarm + 踩坑） | 2-3 |
| **测试** | 4-6 个新测试文件 | 1.5 |
| **真机调试** | 风控踩坑 | 1-2 |
| **合计** | | **8-10** |

## 风险点

1. **动态流接口"有运气成分"**：即便 wbi + buvid3 + dm_img 全做对，仍可能被 -352/-412 风控。UI 标注"实验功能"，检测失败时静默不扰 + AppLogger 记录
2. **wbi key 每日更替**：缓存 + 失效重取，如果 nav 接口也挂了则降级到"仅直播监控"
3. **B 站 API 随时可能变**：不内嵌 API 文档（合规风险），只实现签名 + 调用
4. **60s 轮询视频列表**：比动态流稳定，但仍需关注风控。视频列表接口 `space/wbi/arc/search` 文档未标注频率限制，实测 60s 应可接受
5. **动态流 5min Alarm**：Doze 下可能被节流到 15min，这是平台限制无法绕开

## 测试计划

| 测试文件 | 覆盖 |
|---|---|
| `domain/ActivityDeciderTest.kt`（新） | 纯函数：跳变检测、首次不提、各类型决策 |
| `util/WbiSignerTest.kt`（新） | 签名算法正确性（固定 key → 固定 w_rid）、key 置换表 |
| `api/BilibiliActivityApiTest.kt`（新） | JSON 解析、网络错误处理 |
| `service/LiveCheckServiceTest.kt`（改） | 加：监控视频开关时周期检测调 API、不调 API |
| `MainActivityTest.kt`（改） | 加：活动监控设置对话框、开关落 prefs |
| `util/PreferenceManagerTest.kt`（改） | 加：11 个新键 round-trip + 默认值 |

## Token 估算

AI 辅助全流程约 **120-200 万 token**，动态流风控踩坑是变量。
