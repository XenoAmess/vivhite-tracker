# 提醒铃声自定义设计

## 背景

提醒铃声来源统一走 `AlertSoundProvider`，支持**内置铃声池 / 系统铃声库 / 用户音频文件**三种来源。开播提醒与活动提醒（新视频/置顶/动态）共用同一个铃声源，均受用户选择的铃声控制。

当前发声点只有一处：`LiveCheckService.playAlertSound()`。**全屏提醒页 `AlertActivity` 只负责锁屏展示，不发声**——服务是铃声的唯一所有者，避免两套播放器叠音（LiveCheckService.kt:622 注释）。

> 早期设计曾规划 `AlertActivity.playAlarm()` 与 Service 各自响铃，落地时合并为 Service 单点发声。

## 架构

```
MainActivity「提醒铃声」入口
    ↓
铃声设置对话框
  ① 内置铃声池（单选 + 试听）
  ② 从系统铃声库选 → ACTION_RINGTONE_PICKER
  ③ 从音频文件选 → ACTION_OPEN_DOCUMENT audio/*
  ④ 恢复默认
    ↓ 存
PreferenceManager
  alert_sound_uri  (String，前缀编码来源)
  alert_sound_title (String，UI 展示名)
    ↓ 读
domain/AlertSoundDecider (纯函数，无 Android 依赖)
  解析 uri 字符串 → SoundSource sealed class
    ↓ 用
util/AlertSoundProvider (有 Android 依赖)
  决策 + 加载 + 四档兜底
    ↓ 唯一发声点
LiveCheckService.playAlertSound (Media3 ExoPlayer)
```

## prefs 编码方案

`alert_sound_uri` 用前缀编码来源（2 个键搞定，不拆 4 个）：

| 前缀 | 样本 | 含义 |
|---|---|---|
| `builtin:` | `builtin:alert_1` | 内置池指定项 |
| `system:` | `system:content://settings/system/alarm_alert` | 系统铃声库 |
| `file:` | `file:content://com.android.providers.downloads.documents/456` | 用户音频文件（SAF） |
| `""` (空) | | 默认 → 内置池第 1 个 |

`alert_sound_title`：用户可见名，如「海愿」「晨曦 (系统)」「我的录音.mp3」。

## domain 层（纯函数，无 Android 依赖）

`AlertSoundDecider`（domain/AlertSoundDecider.kt）：解析 uri 字符串 → `SoundSource` sealed class

```kotlin
sealed class SoundSource {
    object Default : SoundSource()
    data class BuiltIn(val key: String) : SoundSource()
    data class System(val uri: String) : SoundSource()
    data class File(val uri: String) : SoundSource()
}
```

- 空白/未知前缀 → `Default`（向后兼容旧版本/损坏数据）。
- 纯 JVM 可测，不依赖 `R.raw` 或 `Uri`。
- 内置 key 与 enum 的对应校验放在加载层（AlertSoundProvider）做，这里保持纯函数。

## util 层（有 Android 依赖）

`AlertSoundProvider.setupDataSource(context, player, uriPref)`（util/AlertSoundProvider.kt）：
把 [Player]（Media3 ExoPlayer）设置到正确数据源，含四档兜底：

```
1. 用户选的（file/system/builtin）→ 尝试加载
2. 失败 → 内置默认（BuiltInSound.DEFAULT）
3. 再失败 → 系统闹钟铃声（TYPE_ALARM → NOTIFICATION → RINGTONE）
4. 全部失败 → 返回 false，调用方静默处理 + AppLogger.e
```

每级失败打 AppLogger 日志（起播前落 `alert sound source:` 行，方便从「运行日志」页查证实际播的哪首）。

## 内置铃声池

`BuiltInSound` enum（util/AlertSoundProvider.kt），`DEFAULT = CL_1`。音频资源在 `res/raw/`，内容由开发者提供：

| key | 资源 | 标题 | 备注 |
|---|---|---|---|
| `alert_1` | `res/raw/alert_1.mp3` | 海愿 | 默认值 |
| `alert_2` | `res/raw/alert_2.ogg` | 春弦 | |
| `alert_3` | `res/raw/alert_3.ogg` | Ad astra | |
| `alert_4` | `res/raw/alert_4.ogg` | BATTLEPLAN ARCLIGHT | |
| `alert_5` | `res/raw/alert_5.ogg` | 星之所在 | |
| `alert_6` | `res/raw/alert_6.ogg` | 遊園施設 | |
| `alert_7` | `res/raw/alert_7.ogg` | Dear Milady de Vtuber | |

内置资源用 `android.resource://<package>/<resId>` Uri + `setMediaItem` 方式加载，不需要复制到临时文件。

## SAF 持久化权限

用户选音频文件后，必须立即调用：
```kotlin
contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
```
否则进程被杀后 uri 读不出，铃声播不出来。Manifest 不需要加权限声明（OPEN_DOCUMENT 是运行时授 uri permission）。

| 来源 | 进程被杀后 | 处理 |
|---|---|---|
| 系统铃声库（ACTION_RINGTONE_PICKER） | 系统铃声稳定可读 | 啥都不用做 |
| 用户音频文件（OPEN_DOCUMENT audio/*） | 默认只当前进程可读 | `takePersistableUriPermission` |
| 部分 ROM 私有铃声 uri | 重启读不出 | picker 过滤 + 兜底链覆盖 |

## 发声点（Service 唯一）

`LiveCheckService.playAlertSound()`（service/LiveCheckService.kt:662）用 Media3 ExoPlayer：

> **勿扰时段例外**：`triggerAlert`/`triggerActivityAlert` 在勿扰窗口内（`QuietHoursDecider`）不调用
> `playAlertSound`，只发静音通知（`setSilent`），不响铃不震动不全屏。见 `docs/new-features-plan.md`。

```kotlin
val player = playerFactory(context)              // ExoPlayer.Builder
player.setAudioAttributes(Media3AudioAttributes USAGE_ALARM, handleAudioFocus=false)
alertSoundProvider.setupDataSource(context, player, prefs.getAlertSoundUri())
player.repeatMode = REPEAT_MODE_ONE              // gapless 循环
player.playWhenReady = true
// 10 秒后自动停止（身份校验，防旧定时器误杀新播放器）
```

要点：
- **USAGE_ALARM** 语义、**REPEAT_MODE_ONE** gapless 循环、**10 秒自动停止**、`onDestroy` 同步停（`stopAlertSoundSync`）。
- 开播提醒与活动提醒撞车时先释放旧播放器，防止双音轨循环泄漏。
- 播放器引用提升为 `alertPlayer` 字段（internal 便于测试），Robolectric 无法构造 ExoPlayer，真机/模拟器用默认实现。
- `AlertActivity` 不发声：它通过 `ACTION_STOP_ALERT`/`ACTION_WATCH_LIVE` 通知服务，30 秒自动关闭。

## 文件清单（现状）

| 文件 | 类型 |
|---|---|
| `res/raw/alert_1.mp3`、`res/raw/alert_2.ogg` … `alert_7.ogg` | 内置铃声池（内容由开发者提供） |
| `res/layout/dialog_alert_sound.xml`、`res/layout/item_builtin_sound.xml` | 铃声设置对话框 |
| `res/layout/expand_section_ringtone.xml` | 主界面「提醒铃声」折叠区 |
| `util/PreferenceManager.kt` | alert_sound_uri / alert_sound_title 两键 |
| `domain/AlertSoundDecider.kt` | 纯函数解析 |
| `util/AlertSoundProvider.kt` | 加载 + 兜底 + BuiltInSound enum |
| `MainActivity.kt` | 铃声入口 + 对话框 + 3 个 launcher + SAF 持久化 |
| `service/LiveCheckService.kt` | `playAlertSound()` 唯一发声点 |
| `AlertActivity.kt` | 仅全屏展示，不发声 |

## 测试

- `domain/AlertSoundDeciderTest.kt`：4 种前缀解析、空值默认、非法值兜底、编码 round-trip。
- `util/AlertSoundProviderTest.kt`：四档兜底链。
- `service/LiveCheckServiceTest.kt`：playAlertSound 编排（provider/playerFactory 注入 fake）。

## 风险点

1. 国产 ROM 对 SAF 持久化权限实现不一致（MIUI/EMUI 偶有清理情况），兜底链 + 日志覆盖。
2. `ACTION_RINGTONE_PICKER` 在部分 ROM 返回的 uri 跨进程重启后读不出，同上兜底。
3. 内置铃声池音频文件版权由用户提供。
4. 试听按钮播 2–3 秒再停，避免和真实提醒混淆。
