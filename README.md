# 牢白播了吗

[![Coverage](https://img.shields.io/endpoint?url=https://xenoamess.github.io/vivhite-tracker/coverage.json)](https://xenoamess.github.io/vivhite-tracker/report/coverage.html)

一款专为白绮（Bilibili直播间: 11258892）打造的安卓应用，用于监控直播间开播状态，并在开播时通过响铃、震动和屏幕提醒通知用户。

![frontispiece.png](resources/frontispiece.png)

可是我觉得很神圣啊

。。。至少很可爱啊

## 功能特性

1. **手动启停与自动恢复**
   - 新安装默认不启动监控，用户点击开始后以该开关为准
   - 已开启监控但服务被系统回收时，重新打开应用会自动恢复
   - 使用前台服务（Foreground Service）保持应用在后台存活
   - 通过通知栏显示当前监控状态
   - 支持开机自启动

2. **定期检测**
   - 默认每分钟查询一次直播间状态，可选 15 秒实时或 5 分钟省电档
   - 使用B站官方API获取实时数据
   - 固定监控直播间：**11258892（白绮）**

3. **开播提醒**
   - 从未开播转为已开播时触发提醒
   - 提醒方式：响铃 + 震动 + 全屏弹窗 + 通知

4. **状态图标**
   - 应用图标和界面图标根据直播状态自动切换
   - 通知栏图标同步显示当前状态

5. **电池优化提示**
   - 智能检测电池优化设置
   - 引导用户关闭电源管理以保证应用正常运行

6. **绮迹手账**
   - 自动记录直播场次、标题变化、人气采样和粉丝快照
   - 支持心情事件、统计、导入导出、海报和备份

## 项目结构

```
BilibiliLiveMonitor/
├── app/
│   ├── src/main/
│   │   ├── java/com/bilibili/livemonitor/
│   │   │   ├── MainActivity.kt              # 主界面
│   │   │   ├── AlertActivity.kt             # 开播提醒全屏弹窗
│   │   │   ├── LiveMonitorApp.kt            # Application类
│   │   │   ├── api/
│   │   │   │   └── BilibiliApi.kt           # B站API接口
│   │   │   ├── service/
│   │   │   │   └── LiveCheckService.kt      # 前台监控服务
│   │   │   ├── receiver/
│   │   │   │   ├── BootReceiver.kt          # 开机启动接收器
│   │   │   │   └── ServiceRestartReceiver.kt # 服务重启接收器
│   │   │   └── util/
│   │   │       └── PreferenceManager.kt     # 偏好设置管理
│   │   ├── res/
│   │   │   ├── layout/                      # 布局文件
│   │   │   ├── drawable/                    # 图形资源（on/off图标）
│   │   │   ├── mipmap-*/                    # 应用图标
│   │   │   └── values/                      # 颜色、字符串、主题
│   │   └── AndroidManifest.xml
│   └── build.gradle.kts
├── build.gradle.kts
├── settings.gradle.kts
└── gradle.properties
```

## 技术栈

- **语言**: Kotlin
- **最低SDK**: API 26 (Android 8.0)
- **编译/目标SDK**: API 36
- **构建工具**: JDK 17、Gradle Wrapper 9.7.0、AGP 9.3.1（内置 Kotlin）
- **主要依赖**:
  - AndroidX Core & AppCompat
  - Material Design Components
  - Kotlin Coroutines
  - Room
  - Jsoup (网页解析)

## 权限说明

| 权限 | 用途 |
|------|------|
| `INTERNET` | 访问B站API查询直播间状态 |
| `FOREGROUND_SERVICE` | 保持后台服务运行 |
| `POST_NOTIFICATIONS` | 显示通知栏状态 |
| `VIBRATE` | 开播时震动提醒 |
| `WAKE_LOCK` | 开播时唤醒屏幕 |
| `REQUEST_IGNORE_BATTERY_OPTIMIZATIONS` | 请求关闭电池优化 |
| `SYSTEM_ALERT_WINDOW` | 显示全屏开播提醒 |
| `RECEIVE_BOOT_COMPLETED` | 开机自启动 |

## 安装方法

### 方式一: Android Studio

1. 使用 Android Studio 打开项目
2. 等待 Gradle 同步完成
3. 连接设备或启动模拟器
4. 点击运行按钮 (`Shift + F10`)

### 方式二: 命令行构建

```bash
# 需要 Android SDK 和 JDK 17；完整本地验证与 CI build job 一致
python3 -m unittest discover -s .github/workflows/tests -v
./gradlew lintDebug testDebugUnitTest jacocoUnitTestReport assembleDebug

# APK 输出路径
app/build/outputs/apk/debug/app-debug.apk
```

## 使用方法

1. **首次启动**
   - 授予通知权限（Android 13+）
   - 根据提示关闭电池优化

2. **自动监控**
   - 首次使用时点击"开始监控"，之后应用和系统重启会按已保存开关恢复
   - 界面显示当前监控状态
   - 应用会在通知栏显示监控状态

3. **手动控制**
   - 点击"停止监控"按钮可随时停止
   - 点击"开始监控"按钮可重新开始

4. **开播提醒**
   - 当白绮开播时，应用会：
     - 播放响铃（10秒）
     - 触发震动
     - 弹出全屏提醒
     - 发送通知

## 注意事项

1. **电池优化**
   - 请务必将应用加入电池优化白名单
   - 否则系统可能会限制后台运行

2. **网络连接**
   - 需要保持网络连接才能正常检测
   - 支持WiFi和移动数据

3. **通知权限**
   - Android 13+ 需要手动授予通知权限
   - 没有通知权限无法保持后台运行

## 自定义配置

固定监控目标的单一来源是 `util/BiliTargets.kt`：

```kotlin
object BiliTargets {
    const val ROOM_ID = 11258892L
    const val MONITOR_MID = 251990176L
}
```

检测间隔通过应用设置选择：实时 15 秒、标准 60 秒（默认）、省电 300 秒。活动监控中的新视频、置顶变化、动态及活动响铃默认开启；勿扰和直播标题变化提醒默认关闭，下播提醒默认开启。

## 图标说明

应用使用自定义图标：
- `resources/on.png` - 开播状态图标
- `resources/off.png` - 关播状态图标

## 许可证

Apache License 2.0
