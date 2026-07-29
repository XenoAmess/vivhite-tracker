package com.bilibili.livemonitor.domain

/**
 * versionName 语义版本解析与比较（纯函数，无 Android 依赖）。
 *
 * 当前 build.gradle 的 versionName 格式：
 * - HEAD 在 tag 上 → "1.3.0"
 * - tag 后有 N commit → "1.2.0+6"（"+N" 是 tag 后的 commit 数）
 * - 无 tag → "0.0.0+${commit数}"
 *
 * versionCode 撞车场景（如本地 "1.2.0+6" 与远端 v1.3.0 都在 commit 110）：
 * 单比 versionCode 误判为 UpToDate。需要 fallback 到 versionName 语义比较。
 *
 * 比较优先级：MAJOR > MINOR > PATCH > SUFFIX。
 * SUFFIX 仅在 MAJOR.MINOR.PATCH 相等时用于 tie-break：
 * - local "1.3.0+6" vs remote "1.3.0" → SUFFIX(null)=-1 < 6，remote 更新
 * - local "1.3.0+6" vs remote "1.3.0+10" → 6 < 10，remote 更新
 * - local "1.3.0" vs remote "1.3.0" → 完全相等，UpToDate
 */
object VersionName {

    data class Version(val major: Int, val minor: Int, val patch: Int, val suffix: Int?)

    /**
     * 解析 versionName。失败返回 null（caller 应 fallback 到 UpToDate，不误报）。
     *
     * 支持：
     * - "1.3.0"     → (1, 3, 0, null)
     * - "1.2.0+6"   → (1, 2, 0, 6)
     * - "0.0.0+110" → (0, 0, 0, 110)
     * - "1.1.97"    → (1, 1, 97, null) (旧版 commit 数 ≥ 100 时)
     */
    fun parse(name: String): Version? {
        val regex = Regex("""^(\d+)\.(\d+)\.(\d+)(?:\+(\d+))?$""")
        val m = regex.matchEntire(name.trim()) ?: return null
        return Version(
            major = m.groupValues[1].toInt(),
            minor = m.groupValues[2].toInt(),
            patch = m.groupValues[3].toInt(),
            suffix = m.groupValues[4].toIntOrNull()
        )
    }

/**
 * 远端是否比本地更新。返回 true 表示远端新；相等或更旧返回 false。
 *
 * 比较规则：
 * - MAJOR > local.MAJOR → 新
 * - MINOR / PATCH 同理
 * - 三段全等：UpToDate（不参与 SUFFIX 比较）
 *
 * 为什么忽略 SUFFIX：
 *   versionName 在 build 时由 `git describe --tags --long` 推导。
 *   本地 build 时可能没有某个远端 release tag（tag 是 release build 后打的），
 *   本地 versionName = "1.2.0+6"，远端 release = "1.3.0"，但两者可能指向
 *   **同一 commit**（commit 数相同时）。此时 versionCode 已相等（commit 数），
 *   versionName 比较应只在 MAJOR.MINOR.PATCH 不等时报"新"，三段相等则 UpToDate。
 *
 * 例子：
 * - 本地 `1.2.0+6` vs 远端 `1.3.0`：MAJOR.MINOR 不等 → 新
 * - 本地 `1.3.0+6` vs 远端 `1.3.0`：三段相等 → UpToDate（同 commit）
 * - 本地 `1.3.0` vs 远端 `1.3.0`：完全相等 → UpToDate
 */
fun isRemoteNewer(remote: Version, local: Version): Boolean {
    if (remote.major != local.major) return remote.major > local.major
    if (remote.minor != local.minor) return remote.minor > local.minor
    if (remote.patch != local.patch) return remote.patch > local.patch
    // 三段相等：忽略 SUFFIX（git describe 的 +N 不可直接比较）
    return false
}
}