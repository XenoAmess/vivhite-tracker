package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private class FakeUpdateChecker(
        private val responses: Map<String, String?>
    ) : UpdateChecker() {
        override suspend fun httpGet(url: String): String? = responses[url]
    }

    private val apiUrl = UpdateChecker.LATEST_RELEASE_API
    private val versionJsonUrl = "https://example.com/version.json"

    private fun releaseJson(assets: String) = """{
        "tag_name": "v1.1.2",
        "body": "更新日志内容",
        "assets": [$assets]
    }"""

    private val fullAssets = """
        {"name": "vivhite-tracker-1.1.92.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.92.apk"},
        {"name": "version.json", "browser_download_url": "$versionJsonUrl"}
    """

    @Test
    fun `versionJson 优先于文件名解析`() = runBlocking {
        // 文件名写的是 92，version.json 写的是 95：以 version.json 为准
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to """{"versionCode":95,"versionName":"1.1.95"}"""
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals(95, info.versionCode)
        assertEquals("1.1.95", info.versionName)
        assertEquals("https://example.com/vivhite-tracker-1.1.92.apk", info.apkUrl)
        assertEquals("更新日志内容", info.changelog)
    }

    @Test
    fun `无 versionJson 时回退 APK 文件名解析`() = runBlocking {
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(
                    """{"name": "vivhite-tracker-1.1.92.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.92.apk"}"""
                )
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        assertEquals(92, (state as UpdateDecider.UpdateState.UpdateAvailable).info.versionCode)
    }

    @Test
    fun `远端不更新时 UpToDate`() = runBlocking {
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to """{"versionCode":92,"versionName":"1.1.92"}"""
            )
        )
        assertEquals(
            UpdateDecider.UpdateState.UpToDate,
            checker.checkLatestRelease(localVersionCode = 92)
        )
    }

    @Test
    fun `网络失败时 Error`() = runBlocking {
        val checker = FakeUpdateChecker(emptyMap())
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `响应非法 JSON 时 Error`() = runBlocking {
        val checker = FakeUpdateChecker(mapOf(apiUrl to "not json"))
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `旧格式 Release 无版本信息时 Error`() = runBlocking {
        // 老 Release 只有 app-release.apk，无法确定远端版本号
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(
                    """{"name": "app-release.apk", "browser_download_url": "https://example.com/app-release.apk"}"""
                )
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `versionJson 请求失败时回退文件名解析`() = runBlocking {
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to null
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91)
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        assertEquals(92, (state as UpdateDecider.UpdateState.UpdateAvailable).info.versionCode)
    }
}
