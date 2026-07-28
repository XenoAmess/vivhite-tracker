# 提醒铃声自定义设计

## 背景

当前应用的开播提醒铃声**硬编码**为系统闹钟铃声，在两处独立实现（逻辑完全相同）：

- `service/LiveCheckService.kt` `playAlertSound()`：后台响铃，10 秒自停
- `AlertActivity.kt` `playAlarm()`：锁屏全屏页响铃，用户点按钮才停

取 URI 逻辑：`RingtoneManager.getDefaultUri(TYPE_ALARM) ?: TYPE_NOTIFICATION ?: TYPE_RINGTONE`，无 prefs 键、无自带音频资源。

## 目标

1. **应用自带铃声**：内置铃声池（3–5 个），用户可在其中切换
2. **用户自定义选铃声**：支持系统铃声库 + 任意音频文件两种来源
3. 两处发声点（Service / AlertActivity）使用**同一个**铃声源

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
  决策 + 加载 + 三档兜底
    ↓ 两个调用点共用
LiveCheckService.playAlertSound
AlertActivity.playAlarm
```

## prefs 编码方案

`alert_sound_uri` 用前缀编码来源（2 个键搞定，不拆 4 个）：

| 前缀 | 样本 | 含义 |
|---|---|---|
| `builtin:` | `builtin:alert_default_1` | 内置池指定项 |
| `system:` | `system:content://settings/system/alarm_alert` | 系统铃声库 |
| `file:` | `file:content://com.android.providers.downloads.documents/456` | 用户音频文件（SAF） |
| `""` (空) | | 默认 → 内置池第 1 个 |

`alert_sound_title`：用户可见名，如「经典提醒 1」「晨曦 (系统)」「我的录音.mp3」。

## domain 层（纯函数，无 Android 依赖）

`AlertSoundDecider`：解析 uri 字符串 → `SoundSource` sealed class

```kotlin
sealed class SoundSource {
    object Default : SoundSource()
    data class BuiltIn(val key: String) : SoundSource()
    data class System(val uri: String) : SoundSource()
    data class File(val uri: String) : SoundSource()
}
```

纯 JVM 可测，不依赖 `R.raw` 或 `Uri`。

## util 层（有 Android 依赖）

`AlertSoundProvider`：操作 MediaPlayer 设置数据源，含三档兜底：

```
1. 用户选的（file/system/builtin）→ 尝试加载
2. 失败 → 内置默认（alert_default_1）
3. 再失败 → 系统闹钟铃声（保留现有降级链）
4. 全部失败 → 返回 false，调用方静默处理 + AppLogger.e
```

内置资源用 `AssetFileDescriptor` 法（不复制到临时文件、省内存）：
```kotlin
val afd = context.resources.openRawResourceFd(sound.resId)
player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
afd.close()
```

`BuiltInSound` enum（key / resId / title），`DEFAULT = CLASSIC_1`。

## SAF 持久化权限（需求 2 的真正难点）

用户选音频文件后，必须立即调用：
```kotlin
contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
```
否则进程被杀后 uri 读不出，铃声播不出来。Manifest 不需要加权限声明（OPEN_DOCUMENT 是运行时授 uri permission）。

各来源的持久化差异：

| 来源 | 进程被杀后 | 处理 |
|---|---|---|
| 系统铃声库（ACTION_RINGTONE_PICKER） | 系统铃声稳定可读 | 啥都不用做 |
| 用户音频文件（OPEN_DOCUMENT audio/*） | 默认只当前进程可读 | `takePersistableUriPermission` |
| 部分 ROM 私有铃声 uri | 重启读不出 | picker 过滤 + 兜底链覆盖 |

## 两端发声点改动

`LiveCheckService.playAlertSound` 和 `AlertActivity.playAlarm` **只改取 uri 那一段**，把硬编码的 `RingtoneManager.getDefaultUri(...)` 链替换为 `AlertSoundProvider.setupDataSource(context, player, prefs.getAlertSoundUri())`。MediaPlayer 的 `USAGE_ALARM` / `isLooping` / 10 秒自停 / onDestroy 停 保留。

## 文件改动清单

| 文件 | 类型 |
|---|---|
| `res/raw/alert_default_1.ogg` 等 4 个 | 新增（占位，后续替换） |
| `res/layout/dialog_alert_sound.xml` | 新增 |
| `res/layout/item_builtin_sound.xml` | 新增 |
| `res/layout/activity_main.xml` | 改（加「提醒铃声」行） |
| `util/PreferenceManager.kt` | 改（加 2 键） |
| `domain/AlertSoundDecider.kt` | 新增 |
| `util/AlertSoundProvider.kt` | 新增（含 `BuiltInSound` enum） |
| `MainActivity.kt` | 改（铃声入口 + 对话框 + 3 launcher + SAF 持久化） |
| `service/LiveCheckService.kt` | 改（`playAlertSound` 取 uri） |
| `AlertActivity.kt` | 改（`playAlarm` 取 uri） |
| `AndroidManifest.xml` | 不改 |

## 测试计划

| 测试文件 | 覆盖 |
|---|---|
| `domain/AlertSoundDeciderTest.kt`（新） | 纯函数：4 种前缀解析、空值默认、非法值兜底、编码 round-trip |
| `util/AlertSoundProviderTest.kt`（新） | 三档兜底链（Robolectric） |
| `util/PreferenceManagerTest.kt`（改） | 新键 round-trip + 默认值 |
| `service/LiveCheckServiceTest.kt` | 现有测试不破（alertPlayer 赋值不变） |
| `AlertActivityTest.kt` | 现有测试不破 |
| `MainActivityTest.kt`（改） | 铃声入口 → 对话框 → 选内置落 prefs |

## 风险点

1. 国产 ROM 对 SAF 持久化权限实现不一致（MIUI/EMUI 偶有清理情况），兜底链 + 日志覆盖
2. `ACTION_RINGTONE_PICKER` 在部分 ROM 返回的 uri 跨进程重启后读不出，同上兜底
3. 内置铃声池音频文件版权由用户提供
4. 试听按钮播 2–3 秒再停，避免和真实提醒混淆
