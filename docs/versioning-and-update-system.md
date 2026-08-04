# 版本管理与应用更新方案

本文记录「牢白播了吗」当前的版本生成、签名、发布和应用内更新机制，供维护发布工作流或更新客户端时参考。

## 总览

项目有两个更新通道：

- 稳定版：推送 `v*` tag 后由 GitHub Actions 构建，发布到 GitHub Releases。
- Beta 内测版：`master` 每次 push 后构建，APK 和元数据发布到 GitHub Pages；历史底包和补丁保存于 `beta-archive` GitHub Release。

两个通道都使用 `versionCode` 判断是否有更新，并通过 `version.json` 传递版本、更新说明、整包校验和增量更新元数据。客户端优先尝试可用的增量链，失败时自动回退整包下载。

```text
Git history + v* tag
        |
        +-- build.gradle.kts -> versionCode/versionName/GIT_HASH
        |
        +-- v* tag -> GitHub Release -> stable version.json / APK / patches
        |
        +-- master push -> GitHub Pages beta -> beta version.json / APK
                                         |
                                         +-> beta-archive -> history APKs / patches
```

## 版本模型

版本配置定义于 `app/build.gradle.kts`。

### versionCode

`versionCode` 使用当前提交在完整 Git 历史中的提交总数：

```kotlin
git rev-list --count HEAD
```

其作用是提供 Android 安装包覆盖安装和客户端更新比较所需的单调递增整数。正常情况下，后续提交的构建会拥有更大的 `versionCode`。

此策略要求：

- 构建目录必须是 Git 仓库，且命令可执行。
- CI checkout 必须使用 `fetch-depth: 0`，不能使用浅克隆。
- 发布历史不能被会降低提交总数的重写操作破坏；迁仓、过滤历史或强制改写主线前应评估 `versionCode` 的连续性。

### versionName

`versionName` 使用最近的 `v*` tag 通过 `git describe --tags --long --match v*` 推导：

| Git 状态 | versionName |
| --- | --- |
| `HEAD` 正好位于 `v1.8.0` | `1.8.0` |
| `v1.8.0` 后有 3 个提交 | `1.8.0+3` |
| 没有匹配的 tag | `0.0.0+<versionCode>` |

Tag 的 `v` 前缀仅为 Git tag 命名约定，不会写入应用显示版本。`+N` 用于区分某个正式版本之后的第 N 个构建。

### 构建标识与应用内更新日志

- `BuildConfig.GIT_HASH` 为当前提交的 8 位短哈希，用于首页展示和问题排查。
- 构建过程生成 `CHANGELOG.txt` 到 APK assets。它按 `v*` tag 列出每版日期和最多 20 条提交摘要，供关于页展示。
- 生成更新包时的 `version.json.changelog` 也取提交摘要。应用内更新提示优先使用该字段，而不是 GitHub Release body，使稳定版和 Beta 的更新说明来源一致。

## 签名与构建变体

### Debug

Debug 变体优先使用仓库内的 `app/debug.keystore`；CI 从 `DEBUG_KEYSTORE_BASE64` Secret 解码生成该文件。若本地不存在该文件，Gradle 回退到系统默认 debug keystore。

固定 debug 签名使多台 CI 或开发机生成的 debug 包在密钥一致时可相互覆盖安装。

### Release

Release 变体使用 `release` signingConfig：

- CI 将 `SIGNING_KEY` Secret 解码为 `app/release.keystore`。
- `SIGNING_KEY_FILE` 指向 keystore 文件；口令和别名由 `KEY_STORE_PASSWORD`、`ALIAS`、`KEY_PASSWORD` 传入。
- 当前发布约定中，release 和固定 debug keystore 是同一份密钥，因而 debug/release 和 Beta/stable 包可覆盖安装。

这是一项为侧载更新设计的项目约定。修改 keystore、别名或签名配置会使已安装包无法覆盖安装，并会使增量补丁的底包校验失效。

## 稳定版发布

工作流：`.github/workflows/android-release.yml`。

### 触发与构建

推送任意匹配 `v*` 的 tag 会触发发布工作流：

1. 使用完整 Git 历史 checkout。
2. 配置 Temurin JDK 17。
3. 解码 release keystore。
4. 运行 `./gradlew assembleRelease`。
5. 以当前 tag 和提交历史生成版本化 APK、`changelog.txt` 和初始 `version.json`。

产物 APK 名为：

```text
vivhite-tracker-<versionName>.apk
```

例如 tag `v1.8.0` 产生 `vivhite-tracker-1.8.0.apk`。

### 发布资产

发布物 = `ApkNormalized`（确定性打包）+ `apksigner 34.0.0` 重签（字节一致性硬要求，v35+ 破坏）。
随后工作流下载 ApkDiffPatch v1.8.1 工具链并运行 `build_delta_chains.py`，最后创建 GitHub Release，上传：

- `vivhite-tracker-<versionName>.apk`：完整 release APK（归一化+重签的发布物）。
- `version.json`：版本和更新元数据（`apkSha256/apkSize` 对发布物计算）。
- `patch-<fromVersionCode>-to-<toVersionCode>.patch`：可用的稳定版增量补丁（ApkDiffPatch）。

`softprops/action-gh-release` 会生成 GitHub Release notes。客户端的更新说明则优先使用 `version.json` 中的 `changelog`。

## Beta 内测通道

工作流：`.github/workflows/android-ci.yml`。

`master` 的 push 在 lint、单测和 debug APK 构建完成后，会额外执行 Beta 发布流程：

1. 使用 release 变体构建签名 APK，避免侧载 `debuggable=true` 包带来更重的安全提示。
2. 按与 Gradle 相同的规则生成 `versionCode`、`versionName`、更新说明和 `version.json`。
3. 将 APK 固定命名为 `vivhite-tracker-beta.apk`。
4. 运行 `build_beta_chains.py` 生成补丁和升级链。
5. 将当前 APK 和 `version.json` 作为 workflow artifact 交给 `coverage-pages` job。
6. 部署至 GitHub Pages：

```text
https://xenoamess.github.io/vivhite-tracker/beta/version.json
https://xenoamess.github.io/vivhite-tracker/beta/vivhite-tracker-beta.apk
```

Beta 通道的 APK URL 固定，版本判断完全依赖 `version.json`。因此同一 URL 被新构建覆盖不会让客户端误判旧包为新包。

### beta-archive

Beta 的历史底包不能由 Pages 获得，所以 `.github/workflows/build_beta_chains.py` 维护一个名为 `beta-archive` 的 GitHub Release：

- 初次运行时创建该 Release，并将其 tag 固定在根提交，避免它影响 `git describe` 的正式版本推导。
- 保存最近 8 个 Beta APK，文件名为 `beta-<versionCode>.apk`。
- 保存可复用的补丁和 `beta-history.json`。
- 每次发布后裁剪较旧 APK 及其关联补丁。

该 Release 仅作为增量更新底包和补丁的存档，用户下载最新 Beta 仍走 GitHub Pages。

## version.json 协议

初始元数据由工作流生成；增量脚本补充校验和链路字段。典型结构如下：

```json
{
  "versionCode": 123,
  "versionName": "1.8.0",
  "changelog": "abc1234 feat: ...",
  "apkSha256": "...",
  "apkSize": 12345678,
  "patches": {
    "122": {
      "file": "patch-122-to-123.bspatch",
      "size": 456789,
      "patchSha256": "..."
    }
  },
  "chains": {
    "120": {
      "fromApkSha256": "...",
      "totalSize": 987654,
      "hops": [
        {
          "toVersionCode": 122,
          "url": "https://.../patch-120-to-122.bspatch",
          "size": 400000,
          "patchSha256": "...",
          "resultSha256": "..."
        }
      ]
    }
  }
}
```

字段说明：

| 字段 | 必需性 | 作用 |
| --- | --- | --- |
| `versionCode` | 必需 | 远端 Android 版本号，客户端的首要比较依据。 |
| `versionName` | 必需 | 展示版本；`versionCode` 相同时参与语义比较。 |
| `changelog` | 可选 | 应用内更新说明，优先级高于 GitHub Release body。 |
| `apkSha256` | 可选 | 当前完整 APK 的 SHA-256。 |
| `apkSize` | 可选 | 当前完整 APK 字节数。 |
| `patches` | 稳定版发布脚本内部/历史链构建使用 | 当前 Release 托管的直达补丁元数据。 |
| `chains` | 可选 | 以本地 `versionCode` 为 key 的增量更新链。 |

缺少增量字段不影响普通更新检查，客户端自然回退到完整 APK 下载。`versionCode` 或 `versionName` 缺失/非法时，客户端将该远端元数据视为错误，不会尝试按文件名猜测版本。

## 客户端检查和安装流程

主要代码：

- `api/UpdateChecker.kt`：网络请求、下载和通道地址。
- `domain/UpdateDecider.kt`：解析和纯版本决策。
- `util/IncrementalUpdater.kt`：增量链下载、验证和应用。
- `util/AppUpdater.kt`：下载落盘路径、安装 Intent、安装权限及 Wi-Fi 检测。

### 稳定版检查

`UpdateChecker.checkLatestRelease()` 的流程：

1. 请求 GitHub `releases/latest` API。
2. 从 Release assets 找到版本化 APK 和 `version.json`。
3. 请求并解析 `version.json`。
4. 使用远端 `versionCode` 和 `versionName` 调用 `UpdateDecider.decide()`。
5. 如有更新，使用 `version.json` 的更新说明、APK 哈希、大小及当前本地版本对应的 `chains` 覆盖补充 Release 信息。

稳定版 API 地址：

```text
https://api.github.com/repos/XenoAmess/vivhite-tracker/releases/latest
```

### Beta 检查

`UpdateChecker.checkBetaChannel()` 只下载 Pages 上的 `version.json`，并使用固定的 Beta APK URL。它不依赖 GitHub Release 的“最新发布”语义。

### 决策规则

`UpdateDecider.decide()` 的规则为：

1. 远端没有有效版本信息时返回 `Error`。
2. 远端 `versionCode` 大于本地时返回 `UpdateAvailable`。
3. 两者 `versionCode` 相等时，解析并比较 `versionName`；远端语义版本较新时仍返回 `UpdateAvailable`。
4. 其他情况返回 `UpToDate`。

第 3 步用于防御特殊的版本号碰撞，例如同一提交计数下，Beta 构建版本名与新 tag 版本名不同。

### 整包下载与安装

普通更新将 APK 保存到：

```text
<filesDir>/updates/vivhite-tracker-<versionName>.apk
```

下载成功后，应用经 `FileProvider` 创建 `application/vnd.android.package-archive` 的 `ACTION_VIEW` Intent，交由 Android 系统安装器安装。若应用没有“允许安装未知应用”权限，使用 `ACTION_MANAGE_UNKNOWN_APP_SOURCES` 引导用户到系统设置页。

APK 下载网络异常时会删除未完成文件并返回失败。

## 增量更新

### 生成策略（稳定版：ApkDiffPatch）

稳定版 `build_delta_chains.py` 使用 ApkDiffPatch（`sisong/ApkDiffPatch` v1.8.1，MIT）：

1. 发布物 = `ApkNormalized(新APK)` + `apksigner 34.0.0` 重签（**apksigner v35+ 破坏字节一致性，必须钉 34**，上游 issue #96/#107）。
2. 对最近 8 个历史 release 的**已发布签名 APK** 生成直达补丁：`ZipDiff(old.apk, 发布物, patch)`。
3. 回打自验：`ZipPatch(old.apk, patch, verify.apk)` 与发布物逐字节 `cmp`，不一致丢弃。
4. 补丁不小于发布物一半也丢弃；单跳直达，不构建多跳链。
5. **过渡安全**：只对「已装包内含 `libapkpatch.so`」的 from-version 生成补丁；jbsdiff-only 旧客户端自动全量下载，「检查更新」按钮始终可用（首个带新客户端的 release 无任何链，全员全量）。
6. 把通过验证的补丁 SHA-256、大小和目标 APK SHA-256 写入元数据。

实测（v1.7.0→v1.8.0）：ApkDiffPatch 补丁 **0.58MB**，原 jbsdiff 为 **6.74MB**（缩小 11.6 倍，仅全量 1.5%）。

发布或补丁生成失败不会阻断完整 APK 的发布；缺少补丁或链条时客户端仅下载整包。

### 生成策略（Beta：ApkDiffPatch）

Beta 脚本 `build_beta_chains.py` 与稳定版同一套 ApkDiffPatch 管线（归一化+apksigner34 重签 + ZipDiff 单跳直达 + 回打自验 + lib 守卫），客户端格式统一为 ZiPat1。

### 客户端执行和回退

`IncrementalUpdater.executeChain()` 逐跳执行：

1. 找到当前已安装 APK，计算 SHA-256，必须匹配 `fromApkSha256`。
2. 下载每一跳补丁。
3. 校验补丁 SHA-256。
4. 调用 `ApkPatcher.applyPatch()` 生成下一跳 APK。`ApkPatcher` 按补丁头分派：`ZiPat1`→ApkDiffPatch（`libapkpatch.so`），`BSDIFF40`→jbsdiff（存量兼容）。
5. 校验每一步输出 APK 的 SHA-256。
6. 最后一跳输出到普通整包下载路径，并交由既有安装流程安装。

底包不可读、底包哈希不匹配、网络失败、补丁哈希不匹配、打补丁异常（含 native 缺失）或结果哈希不匹配时，都会删除临时文件并返回 `null`。调用方应继续执行完整 APK 下载，增量更新本身不应成为用户更新的阻塞点。

## 维护约束

- 改动版本推导规则时，必须同步检查 `app/build.gradle.kts`、`android-release.yml` 和 `android-ci.yml` 中的对应逻辑。
- 所有依赖版本号的 workflow checkout 必须保留 `fetch-depth: 0`。
- 正式 tag 应使用 `v<major>.<minor>.<patch>` 格式；非正式 tag 不应意外匹配 `v*`。
- 不要删除或更换已发布版本的 APK 资产，否则稳定版增量链可能无法构建；客户端最终仍能通过最新完整包更新。
- 不要随意删除或移动 `beta-archive`，否则 Beta 增量历史丢失；最新 Beta 的完整下载仍可用。
- 变更签名密钥前必须评估已安装用户的迁移路径。Android 不允许不同签名的 APK 覆盖安装。
- 自建更新通道依赖 GitHub Releases、GitHub API 与 GitHub Pages 可用。网络受限或服务不可用时，客户端应显示检查失败，而不是将其判为“已是最新”。
- 更新相关纯决策与解析应优先补充 `UpdateDeciderTest` 和 `UpdateCheckerTest`；增量链行为应覆盖 `IncrementalUpdaterTest`，必要时通过 instrumented test 验证真实 APK 安装场景。
