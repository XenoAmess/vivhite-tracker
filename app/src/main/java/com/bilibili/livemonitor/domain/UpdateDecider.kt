package com.bilibili.livemonitor.domain

import org.json.JSONObject

// 应用更新检查的状态决策：纯函数，网络/文件操作在 api/UpdateChecker
object UpdateDecider {

    // GitHub releases/latest 响应的原始解析结果
    data class RawRelease(
        val tagName: String,
        val changelog: String,
        val apkUrl: String?,
        val apkFileName: String?,
        val versionJsonUrl: String?
    )

    data class ReleaseInfo(
        val versionCode: Int,
        val versionName: String,
        val apkUrl: String,
        val changelog: String,
        val tagName: String,
        // 增量更新元数据（version.json chains 字段，无则全量下载）
        val apkSha256: String? = null,
        val apkSize: Long = 0,
        val chain: UpdateChain? = null
    )

    // 链中的一跳：一个 bsdiff 补丁及其校验信息
    data class PatchHop(
        val toVersionCode: Int,
        val url: String,
        val size: Long,
        val patchSha256: String,
        val resultSha256: String
    )

    // 从本地版本到目标版本的完整升级链（可能多跳）
    data class UpdateChain(
        val fromApkSha256: String,
        val totalSize: Long,
        val hops: List<PatchHop>
    )

    // version.json 的完整解析结果（chains/patches 为增量更新字段，老格式没有 → 空）
    data class VersionMeta(
        val versionCode: Int,
        val versionName: String,
        val changelog: String?,
        val apkSha256: String?,
        val apkSize: Long,
        val chains: Map<Int, UpdateChain>
    )

    sealed class UpdateState {
        data class UpdateAvailable(val info: ReleaseInfo) : UpdateState()
        object UpToDate : UpdateState()
        data class Error(val reason: String) : UpdateState()
    }

    // 解析 releases/latest 响应；缺 tag_name 或 JSON 非法返回 null
    fun parseLatestRelease(json: String): RawRelease? {
        return try {
            val obj = JSONObject(json)
            val tagName = obj.optString("tag_name").takeIf { it.isNotBlank() } ?: return null
            val changelog = obj.optString("body")
            val assets = obj.optJSONArray("assets")
            var apkUrl: String? = null
            var apkFileName: String? = null
            var versionJsonUrl: String? = null
            if (assets != null) {
                for (i in 0 until assets.length()) {
                    val asset = assets.optJSONObject(i) ?: continue
                    val name = asset.optString("name")
                    val url = asset.optString("browser_download_url")
                        .takeIf { it.isNotBlank() } ?: continue
                    when {
                        name == VERSION_JSON_NAME -> versionJsonUrl = url
                        APK_NAME_REGEX.matches(name) -> {
                            apkUrl = url
                            apkFileName = name
                        }
                    }
                }
            }
            RawRelease(tagName, changelog, apkUrl, apkFileName, versionJsonUrl)
        } catch (e: Exception) {
            null
        }
    }

    // 解析 version.json 完整元数据（含增量更新 chains）。老格式无新字段 → 对应字段为空，
    // 调用方自然走全量下载。核心字段缺失/JSON 非法 → null
    fun parseVersionMeta(json: String): VersionMeta? {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("versionCode", -1)
            val name = obj.optString("versionName")
            if (code <= 0 || name.isBlank()) return null
            val changelog = obj.optString("changelog").takeIf { it.isNotBlank() }
            val apkSha = obj.optString("apkSha256").takeIf { it.isNotBlank() }
            val apkSize = obj.optLong("apkSize", 0)
            val chains = mutableMapOf<Int, UpdateChain>()
            val chainsObj = obj.optJSONObject("chains")
            if (chainsObj != null) {
                for (key in chainsObj.keys()) {
                    val fromVc = key.toIntOrNull() ?: continue
                    val c = chainsObj.optJSONObject(key) ?: continue
                    val fromSha = c.optString("fromApkSha256").takeIf { it.isNotBlank() } ?: continue
                    val total = c.optLong("totalSize", 0)
                    val hopsArr = c.optJSONArray("hops") ?: continue
                    val hops = mutableListOf<PatchHop>()
                    for (i in 0 until hopsArr.length()) {
                        val h = hopsArr.optJSONObject(i) ?: continue
                        val url = h.optString("url").takeIf { it.isNotBlank() } ?: continue
                        val pSha = h.optString("patchSha256").takeIf { it.isNotBlank() } ?: continue
                        val rSha = h.optString("resultSha256").takeIf { it.isNotBlank() } ?: continue
                        hops.add(PatchHop(h.optInt("toVersionCode"), url, h.optLong("size"), pSha, rSha))
                    }
                    // 任何一跳不完整则整条链作废（打补丁链断一环结果就不对）
                    if (hops.isNotEmpty() && hops.size == hopsArr.length()) {
                        chains[fromVc] = UpdateChain(fromSha, total, hops)
                    }
                }
            }
            VersionMeta(code, name, changelog, apkSha, apkSize, chains)
        } catch (e: Exception) {
            null
        }
    }

    // 决策：远端 versionCode 更高 → 有更新；versionCode 相等时比 versionName 语义版本；
    // 都判定为最新 → UpToDate；远端版本信息缺失 → Error。
    //
    // versionCode 撞车场景：本地 "1.2.0+6" 与远端 v1.3.0 都在 commit 110 时
    // 单比 versionCode 会误判 UpToDate。fallback 到 MAJOR.MINOR.PATCH+SUFFIX 比较。
    fun decide(
        localVersionCode: Int,
        localVersionName: String,
        remoteVersion: Pair<Int, String>?,
        raw: RawRelease
): UpdateState {
        if (remoteVersion == null) {
            return UpdateState.Error("远端版本信息不完整")
        }
        val (remoteCode, remoteName) = remoteVersion

        // 第一层：versionCode
        if (remoteCode > localVersionCode) {
            val apkUrl = raw.apkUrl ?: return UpdateState.Error("未找到 APK 下载地址")
            return UpdateState.UpdateAvailable(
                ReleaseInfo(remoteCode, remoteName, apkUrl, raw.changelog, raw.tagName)
            )
        }

        // 第二层：versionCode 相等 → versionName 语义比较
        if (remoteCode == localVersionCode) {
            val localVer = VersionName.parse(localVersionName)
            val remoteVer = VersionName.parse(remoteName)
            if (localVer != null && remoteVer != null
                && VersionName.isRemoteNewer(remoteVer, localVer)
            ) {
                val apkUrl = raw.apkUrl ?: return UpdateState.Error("未找到 APK 下载地址")
                return UpdateState.UpdateAvailable(
                    ReleaseInfo(remoteCode, remoteName, apkUrl, raw.changelog, raw.tagName)
                )
            }
        }

        return UpdateState.UpToDate
    }

    const val VERSION_JSON_NAME = "version.json"
    private val APK_NAME_REGEX = Regex("vivhite-tracker-.+\\.apk")
}
