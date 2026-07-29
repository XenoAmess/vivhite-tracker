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
        val tagName: String
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

    // version.json: {"versionCode":92,"versionName":"1.1.92"}
    fun parseVersionJson(json: String): Pair<Int, String>? {
        return try {
            val obj = JSONObject(json)
            val code = obj.optInt("versionCode", -1)
            val name = obj.optString("versionName")
            if (code > 0 && name.isNotBlank()) code to name else null
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
