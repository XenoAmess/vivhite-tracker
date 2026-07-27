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

    // vivhite-tracker-1.1.92.apk → (92, "1.1.92")；versionName 尾段即 versionCode
    fun parseApkFileName(fileName: String): Pair<Int, String>? {
        val match = APK_NAME_REGEX.matchEntire(fileName) ?: return null
        val versionName = match.groupValues[1]
        val code = versionName.substringAfterLast('.').toIntOrNull() ?: return null
        return code to versionName
    }

    // 决策：远端 versionCode 更高 → 有更新；无法确定远端版本 → Error；否则已最新
    fun decide(
        localVersionCode: Int,
        remoteVersion: Pair<Int, String>?,
        raw: RawRelease
    ): UpdateState {
        if (remoteVersion == null) {
            return UpdateState.Error("远端版本信息不完整")
        }
        val (remoteCode, remoteName) = remoteVersion
        if (remoteCode <= localVersionCode) return UpdateState.UpToDate
        val apkUrl = raw.apkUrl ?: return UpdateState.Error("未找到 APK 下载地址")
        return UpdateState.UpdateAvailable(
            ReleaseInfo(remoteCode, remoteName, apkUrl, raw.changelog, raw.tagName)
        )
    }

    const val VERSION_JSON_NAME = "version.json"
    private val APK_NAME_REGEX = Regex("vivhite-tracker-(\\d+\\.\\d+\\.\\d+)\\.apk")
}
