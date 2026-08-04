# 项目优化计划

> 本计划基于 2026-08-04 全代码库分析（覆盖 app/src/main 44 文件 / 9173 行，app/src/test 40 文件 / 5535 行）。
> 按用户要求**忽略安全相关问题**（usesCleartextTraffic、allowBackup、FGS specialUse 合规等均不在范围内）。

## 总体结论

三个超大文件集中了绝大多数可维护性问题：
- `MainActivity.kt` 2006 行 / 12 类职责（其测试 `MainActivityTest.kt` 2278 行，比被测类还大）
- `PromoImageRenderer.kt` 1681 行 / 20+ 渲染风格 / 263 处绘制调用
- `LiveCheckService.kt` 1110 行 / 20 项职责

其余问题集中在：网络层重复、Alarm 排程重复、状态管理双镜像、并发边角、依赖与构建配置。

## 执行批次

### Batch A — 死代码与失效逻辑清理（低风险，先行）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| A1 | `proguard-rules.pro` 缺失 | `app/build.gradle.kts:75` 引用不存在文件 | 新建最小 `app/proguard-rules.pro`（当前 `isMinifyEnabled=false` 掩盖，开 R8 必炸） |
| A2 | `cancelAlarm()` 无调用 | `service/LiveCheckService.kt:963-976` | 删除 |
| A3 | `ACTION_STATUS_CHANGED` 广播无接收者 | `service/LiveCheckService.kt:610-617`（updateAppIcon）+ 常量 `:1048-1049` | 删除死广播与 `updateAppIcon`；同步清理测试 |
| A4 | `isUserStopped` 静态变量只写不读 | `service/LiveCheckService.kt:1075`（6 写 0 生产读） | 删除；已被 `stopRequestedByUser`/`stopRequestedGeneration` 替代；同步清理测试 |
| A5 | `showActivitySettingsDialog` 与抽屉 section 完全重复 | `MainActivity.kt:1030-1063` vs `bindActivitySection :795-812` | 删除旧对话框，统一入口；同步迁移测试 |
| A6 | `media3-common` 冗余显式依赖 | `app/build.gradle.kts:119` | 删除（exoplayer 传递依赖） |
| A7 | `QqShare.kt:397` 失效注释（引用不存在 `QqShare.bind`） | `util/QqShare.kt:397` | 修正注释 |

### Batch B — LiveCheckService 并发与状态一致性（中风险，需测试护航）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| B1 | `roomId`/`lastStatus` 跨线程 data race | `service/LiveCheckService.kt:46-47`（主线程写 / IO 协程读 `:282,348`） | 加 `@Volatile` |
| B2 | `lastLiveStatus` 静态与 prefs 双镜像漂移 | `onCreate:103-109` 恢复 lastStatus 但静态 `lastLiveStatus` 保持 false | onCreate 用恢复值同步静态 |
| B3 | `checkWakeLock` 覆盖缺口 | `:285` acquire(60s) < 最坏路径 65s；15s 重试 delay 无锁 | 单把锁覆盖 检测+重试 全程，超时覆盖重试路径 |
| B4 | Alarm 节奏漂移 | `:258` isChecking 跳过分支仍重排 | 跳过时改为仅 `ensureDynamicAlarmScheduled()` |
| B5 | `triggerAlert` 10min wakeLock 立即释放（无效锁） | `:620-631` | 移除或注释明确用途 |

### Batch C — 网络层统一（中风险）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| C1 | `BilibiliApi` 3 处手写 HttpsURLConnection 收敛到 `HttpClient` | `api/BilibiliApi.kt:31-53,94-119,126-145` | 给 `HttpClient` 加 timeout/Referer 参数后复用 |
| C2 | `ShareImageLoader` 2 处手写收敛 | `util/ShareImageLoader.kt:22-43,71-78` | 同上 |
| C3 | Chrome/120 UA 3 份合并 | `BilibiliApi.kt:148`、`HttpClient.kt:11-12`、`ShareImageLoader.kt:110` | 单一常量 |
| C4 | 超时值 4 档归一（5s/8s/10s/15s） | 各处 | 集中配置 + 注释差异理由 |

### Batch D — 精确闹钟排程统一（中风险）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| D1 | SDK 分支复制 4 份 | `LiveCheckService.kt:931-953,1013-1021`、`AlarmReceiver.kt:56-77`、`MagicAlarmScheduler.kt:25-30` | 抽 `util/ExactAlarmScheduler`（或 `AlarmScheduler`）统一 fallback 逻辑 |

### Batch E — MainActivity 低风险抽取（高风险项后置）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| E1 | 直播间/空间 Intent 抽纯工具 | `MainActivity.kt:1626-1700` | `util/BilibiliDeepLinks.kt` |
| E2 | `ACTION_SEND` 图片分享 intent 三合一 | `MainActivity.kt:437-443,1283-1291,1492-1498` | `util/ShareImageFactory.buildImageShareIntent(...)` |
| E3 | `withTimeoutOrNull(3000){roomInfoFetcher}` ×4 收敛 | `MainActivity.kt:1224,1255,1304,1353` | 公共 `fetchShareRoomInfo` |
| E4 | QQ 授权回调生命周期守卫 | `MainActivity.kt:1525-1543,1553-1578` | 回调内判 `isFinishing` 或经 `lifecycleScope` |
| E5 | 500ms postDelayed 泄漏 | `MainActivity.kt:1806,1830` | 改 `lifecycleScope.launch { delay }` 或判 `isDestroyed` |
| E6 | 分享入口防抖 | `MainActivity.kt` 分享入口 | 参照 `isServiceStarting` 互斥写法 |

### Batch F — PreferenceManager 收敛（低风险）

| # | 项 | 位置 | 动作 |
|---|---|---|---|
| F1 | `getRecentLastStatus` 分层倒挂 | `util/PreferenceManager.kt:250-258` | 归位到 `LiveStateDecider` 消费侧 / 删除，服务直接用 `restoreLastStatus` |

### 明确不做（成本/风险不成比例）

- **MainActivity 整体拆分**（ShareController/UpdateController/MagicPeriodDialogFragment/设置抽屉 BottomSheet）：收益大但需连带迁移 2278 行测试，留作后续专项。
- **PromoImageRenderer 拆分/参数化**：自包含、已有测试、行为即视觉，改动风险高。
- **jbsdiff 替换**：~~2013 未维护库~~ —— **已落地**（见「执行状态」增量更新专项）：客户端集成 ApkDiffPatch（jbsdiff 保留为兼容分支），稳定版发布侧切 ZipDiff 生成，补丁 6.74MB→0.58MB。
- **文案全部迁移 strings.xml**：52 处 Toast 工程量大收益低；仅新建文案用资源。

## 执行状态

| Batch | 状态 | 提交 |
|---|---|---|
| A 死代码清理 | ✅ 已落地 | `c69ebcf` |
| B LiveCheckService 并发/状态 | ✅ 已落地 | `12b3ef4` |
| C 网络层统一 | ✅ 已落地 | `4a8178a` |
| D 精确闹钟排程统一 | ✅ 已落地 | `e51d27a` |
| E MainActivity 低风险抽取 | ✅ 已落地 | `5954505` |
| F PreferenceManager 收敛 | ✅ 已落地 | `bcbeb43` |
| **增量更新专项（ApkDiffPatch）** | ✅ 已落地（客户端 `a19e061` + 服务端本次） | 见 `docs/delta-update-alternatives.md` |

增量更新专项要点：客户端打入 4 ABI `libapkpatch.so`、`ApkPatcher` 支持 ZiPat1（jbsdiff 已随双通道迁移移除）；发布侧与 beta 侧均 `ApkNormalized + apksigner34 重签 + ZipDiff` 生成，回打自验，旧客户端自动全量。

B/E/F 落地时发现并修复的额外问题：
- `ShareImageFactory` KDoc 中 `image/*` 会构成嵌套块注释（Kotlin 块注释可嵌套），吞掉 `*/` 导致解析错乱——改为措辞规避。
- Kotlin 命名参数 lambda 无隐式 return 标签（`return@onXXX` 编译失败），QQ 回调守卫改用 `guardActivity` 内联 helper。

## 验证方式

每个 Batch 完成后跑针对性单测；全部结束后执行 AGENTS.md 约定的完整验证：

```bash
JAVA_HOME=~/.jdks/jbr-17.0.14 ./gradlew lintDebug testDebugUnitTest assembleDebug
```

改动完成后按仓库约定自动 commit + push。
