# 增量更新方案选型与设计（bsdiff → 更高效替代）

> 日期：2026-08-04
> 背景：当前增量链路用 `io.sigpipe:jbsdiff`（bsdiff 的 Java 移植）。实测相邻版本补丁 7~7.7MB / 全量 41.7MB（17-18%），远古版本补丁高达全量 83%。评估是否有更高效的替代。

## 1. 调研结论：有没有现成的「安卓可用的 CDC」开源实现？

**结论：没有。** 主流 CDC（Content-Defined Chunking）实现均为 Go/Rust/C 且**不提供 Android .so/JNI 或 JVM 绑定**，无法直接在 Android App 内调用：

| 仓库 | 语言 | Android 可用性 |
|---|---|---|
| `restic/chunker` ⭐356 | Go | ❌ 无 JVM/Android 产物 |
| `jotfs/fastcdc-go` ⭐85 | Go | ❌ |
| `remram44/cdchunking-rs` | Rust | ❌ |
| `fd0/rabin-cdc` | Go | ❌ |
| `baixiangcpp/FileCDC` | C++ | ❌ 无 NDK 产物 |
| 各种 `buzhash` 仓库 | 各语言 | ❌ 仅是哈希原语，无增量协议 |
| `com.github.luben:zstd-jni` | Java/JNI | ⚠️ 有 Android 绑定，但 `--patch-from`（等价 delta 特性）**未暴露**，无法直接做文件增量 |
| Google Play delta（archive-patcher / CDC） | Java | ❌ 闭源/面向 Play 客户端-服务端，非通用库 |

**但调研发现更优的现成方案：APK 专用增量库（非 CDC，但比 bsdiff 小一个数量级，且带 Android 产物）。**

## 2. 关键发现：sisong/ApkDiffPatch（推荐优先评估）

- 仓库：`sisong/ApkDiffPatch`（MIT，⭐364，C++），专为 **Zip(Jar/Apk) 文件 diff/patch** 设计，支持 **Apk v1/v2/v3/v4 签名**。
- **官方 release v1.8.1 直接提供 Android SDK**：`ApkDiffPatch_v1.8.1_sdk_android_zip_patch.zip`，内含 `ApkPatch.java`（JNI）+ 4 个 ABI 的 `libapkpatch.so`（armeabi-v7a ~150KB / arm64-v8a ~430KB）。
- 客户端 API 极简：
  ```java
  // 返回 0 = 成功；patchFilePath 由服务端 ZipDiff 生成
  ApkPatch.patch(oldApkPath, patchFilePath, outNewApkPath,
                 maxUncompressMemory, tempUncompressFilePath, threadNum);
  ```
- 服务端配套 CLI：`ZipDiff(oldZip, newZip, outDiff)` / `ZipPatch(...)`（release 附 Linux/Windows/MacOS 二进制）。

### 官方基准（README 实测，APK 上对比 bsdiff）

| 场景 | newSize | bsdiff | ApkDiffPatch(+zlib) | (+lzma) |
|---|---|---|---|---|
| Chrome 64.123→64.137（相邻） | 43.9MB | 28.9MB | 1.35MB | 1.15MB |
| 微信 661→662（相邻） | 63.6MB | 38.3MB | 1.05MB | 0.94MB |
| 微信 660→661 | 61.3MB | 17.6MB | 1.93MB | 1.66MB |
| 谷歌地图 9.70→9.71 | 50.6MB | 38.0MB | 14.6MB | 11.4MB |
| **平均压缩率** | | **56.3%** | **20.4%** | **16.8%** |

即对真实 APK，ApkDiffPatch 通常比 bsdiff **小 3~36 倍**。本项目相邻 7.7MB → 预计可压到 **~1-2MB**。

### 为什么对 APK 这么有效

APK 是多个**独立 deflate 流**拼成的 zip。bsdiff 在「压缩后字节」上做字节级匹配，一个 entry 内小改动会让该 deflate 流整体漂移，匹配大面积失效。ApkDiffPatch 在**解压/zip 结构层面**对齐 entry，只对变化的 entry 产生 delta，所以无关 entry 全部复用。

### 关键约束：需要发布侧重签名（本项目可行）

README 明确：「ApkDiffPatch can't be used by Android app store, because it requires re-signing apks before diff」。

即发布流程必须变为：**构建 → `ApkNormalized(newApk)`（确定性打包）→ `apksigner` 签名 → 发布该产物**，然后 `ZipDiff(上一版发布的签名 APK, 本次发布的签名 APK) → patch`。客户端把「已安装的旧签名 APK」打补丁成「本次发布的新签名 APK」——字节一致，v2/v3 签名保持有效。

- 本项目的 CI **持有 release 签名密钥**（`SIGNING_KEY` secret + release.keystore），完全满足「发布侧重签名」的前提。
- 当前 jbsdiff 链路本来就要求「客户端已安装 APK 与 CI 的 diff 底包字节一致」（`fromApkSha256` 校验 + 失败回退全量），这套模型原样沿用即可。
- 需在**试点阶段真机验证**：老版本（v1.2.0~v1.8.0，均按旧流程构建）作为 diff 底包，ApkDiffPatch 对非归一化旧包 + 归一化新包的兼容性。

### 风险点

1. **工具链必须固定**：README 警告「You should not modify the zlib version」，否则客户端打补丁用的 deflate 输出与发布端不一致 → 字节不匹配 → 签名失效。需在 CI 里钉死 ApkDiffPatch 版本与 zlib。
2. **发布产物字节会变**：归一化+重签后的新 APK 与当前直接 `assembleRelease` 的产物不同（同 keystore，仍可覆盖安装）。切换发布流程后，旧版本用户的增量底包要用 release 资产里的旧签名 APK。
3. **.so 体积**：4 ABI 的 `libapkpatch.so` 增加 APK 体积 ~1-2MB（可按 ABI 拆分只打 arm 两档）。
4. **需实测**：在真实项目 APK（41.7MB，含 v2 签名）上验证 patch 后字节一致性 + 可安装，再切换。

## 3. 方案对比

| 方案 | 相邻补丁(估) | Android 可用 | 签名保持 | 工作量 |
|---|---|---|---|---|
| **jbsdiff（现状）** | 7.7MB (18%) | ✅ 纯 JVM | ✅ | 0 |
| **A. ApkDiffPatch** | **~1-2MB (3-5%)** | ✅ .so+JNI | ✅（发布侧重签名） | 中 |
| **B. 自研 CDC（纯 JVM）** | ~2-5MB (5-12%) | ✅ 纯 JVM | ✅ | 高 |
| zstd --patch-from | ~7.6%（通用二进制） | ❌ Java 无该特性 | — | — |

## 4. 推荐方案 A：ApkDiffPatch 集成（首选）

### 服务端（.github/workflows）

1. release workflow 构建后追加：
   - `ApkNormalized app-release-unsigned.apk` → `normalized.apk`（确定性 zip 打包）
   - `apksigner sign --ks release.keystore ... normalized.apk` → 发布用 `new.apk`
   - 用 release 资产的上一版签名 APK 作为 `old.apk`，`ZipDiff(old.apk, new.apk, patch-<oldVC>-to-<newVC>.bspatch)`（产物可仍叫 .bspatch 或改名 .patch）
   - `bspatch` 回打自验 + 与 `new.apk` 逐字节比较；失败丢弃补丁不阻断发布
2. `version.json`：`apkSha256/apkSize` 指向发布用的 `new.apk`；`chains[fromVc]` 只出**单跳直达**（补丁够小，多跳链不再需要）。
3. 移除指数回退多跳链逻辑（`build_delta_chains.py` 简化），只对最近 N 个 release（建议 8）生成直达补丁。

### 客户端

1. `app/src/main/jniLibs/<abi>/libapkpatch.so`（4 ABI）+ `ApkPatch.java`（com.github.sisong）。
2. `util/ApkPatcher.applyPatch` 从 `io.sigpipe.jbsdiff.Patch` 换成 `ApkPatch.patch(...)`；签名不变：
   ```kotlin
   ApkPatch.patch(baseApk.absolutePath, patchFile.absolutePath, outFile.absolutePath,
                  maxUncompressMemory = 64L * 1024 * 1024,
                  tempUncompressFilePath = File(context.cacheDir, "apkpatch-tmp").absolutePath,
                  threadNum = 2)
   ```
   注意 ApkPatch 是 native，抛异常/非 0 返回都要按失败处理；`IncrementalUpdater` 的 patchSha256/resultSha256 校验与回退全量逻辑**原样保留**。
3. `ChainPlanner` 可简化：单跳直达，去掉多跳总大小判断（仍保留底包 sha 校验 + 全量兜底）。
4. 依赖清理：移除 `io.sigpipe:jbsdiff` + `commons-compress`（jbsdiff 的 bzip2 依赖）。

### 测试

- 单测：`ApkPatcher` 注入 fake（Robolectric 无法跑 native，打补丁路径留给 instrumented）。
- instrumented：`IncrementalUpdateInstrumentedTest` 用真实旧 APK + 服务端生成的 patch 回打 → 校验 resultSha256 + `PackageManager` 可安装。
- CI release workflow 增加回打自验步骤（失败只丢该补丁）。

## 5. 备选方案 B：自研 CDC（纯 JVM，无 NDK）

适用于「不想引第三方 .so」的取向。完整设计如下。

### 5.1 为什么 CDC 对 APK 也有效

APK 是独立 deflate 流拼接：未变 entry 的压缩字节逐字节相同，只有变化 entry 的压缩流不同。CDC 按**内容指纹**切块，未变字节段自然对齐复用——效果接近「逐 entry delta」，但不需要解析 zip 结构，字节级保真 → 签名保持。

### 5.2 分块算法（FastCDC/gear）

- 滚动哈希：FastCDC gear（单次 64 位乘法 + 移位），窗口 48 字节，比 buzhash 快且分布更均匀。
- 边界判定：`(hash & mask) == 0` 时切块；`mask` 由目标平均块大小决定。
- 参数（对 41.7MB APK）：
  | 参数 | 值 |
  |---|---|
  | 平均块 | 64KB |
  | 最小块 | 32KB（防碎片） |
  | 最大块 | 256KB（防超长） |
- 内容指纹：块内 xxh64（生成侧用块内 xxh64 + 完整 sha256 双校验防误判）。

### 5.3 补丁格式（草案 `VTDC1`）

```
magic   "VTDC1"           6B
newSize u32               4B
opCount u32               4B
ops[]:  tag u8
        COPY:  oldOffset u64, len u32
        DATA:  len u32, zlib(content)          // 每块 zlib 压缩
```

- 生成侧：旧 APK 分块建 `指纹 → [offset]` 索引 → 新 APK 流式分块，指纹命中且 xxh64 一致 → `COPY`；否则 `DATA`（写入新块内容，zlib 压缩）。
- 应用侧：`RandomAccessFile` 读旧 APK，按 ops 顺序拷贝 `COPY` 段 / 解压 `DATA` 段写出，内存上界 ≈ 最大块 256KB + 缓冲区。

### 5.4 生成侧实现（CI）

- 建议用 **JVM 小程序**（复用 gradle 工程新增 `:tools` 源集，release workflow 里 `./gradlew :tools:run`）而非 Python——纯 Python 逐字节滚动哈希在 41.7MB 上要数分钟。
- 产出：patch 文件 + `patchSha256` + `resultSha256`，回打自验与 ApkDiffPatch 同。

### 5.5 客户端

- 新增 `util/CdcPatcher.kt`（纯 JVM）：解析 `VTDC1`，copy+解压重组，输出 sha256 校验。
- `ApkPatcher.applyPatch` 按 magic 分派（`.patch` 走 CdcPatcher，兼容期 `.bspatch` 走 jbsdiff）。
- `version.json`/`ChainPlanner`/`IncrementalUpdater` 改动与方案 A 相同。

### 5.6 测试

- 单测（纯 JVM，可全覆盖）：分块边界（空/极小/大文件）、round-trip 一致、指纹误判兜底、篡改补丁报错、COPC/DATA 混合、sha256 校验失败回退全量。
- instrumented：真实 APK 回打 + 可安装验证。

### 5.7 工作量与风险

- 生成器 + 打补丁器 + 协议 + 测试：估 3-5 人日（比方案 A 多 2-3 倍）。
- 风险：分块参数需对真实 APK 调优；rolling hash 误判必须靠块内强校验兜底；无先例库踩坑自行消化。
- **收益上限低于方案 A**：ApkDiffPatch 有成熟实现 + 官方基准背书，CDC 自研要自己验证字节一致性。

## 6. 决策建议（阶段化）

1. **阶段 0（近期，零成本）**：维持 jbsdiff，但收紧补丁窗口——只对最近 8 个 release 生成**单跳直达**补丁，删除多跳链与远古补丁（64 版之外 83% 毫无价值）；`ChainPlanner` 保留。
2. **阶段 1（试点）**：在 CI 用 ApkDiffPatch 对真实 APK 做 `ZipDiff/ZipPatch` 回打自验，确认：字节一致、v2/v3 签名有效、真机可覆盖安装。产出实测补丁大小对比。
3. **阶段 2（切换）**：按方案 A 集成（服务端 normalize+重签 → 客户端 .so+JNI → 单测/instrumented），灰度一版观察。
4. **CDC（方案 B）**：仅当不接受 NDK/.so 且仍想保留增量时启用；否则以方案 A 为准。

## 7. 参考

- `github.com/sisong/ApkDiffPatch`（MIT，release 附 Android SDK + CLI）
- `github.com/sisong/HDiffPatch`（ApkDiffPatch 的底层通用库，Android libhpatchz.so）
- `github.com/sisong/sfpatcher` / `google/archive-patcher`（应用商店场景，需持有密钥的重签名，模型与本项目侧载不同）
- 本项目现状：`util/ApkPatcher.kt`（jbsdiff）、`util/IncrementalUpdater.kt`、`domain/ChainPlanner.kt`、`.github/workflows/build_delta_chains.py` / `build_beta_chains.py`、`docs/versioning-and-update-system.md`
