package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateDeciderTest {

    private fun releaseJson(
        tagName: String = "v1.1.2",
        body: String = "## What's Changed\\n* feat: something",
        assets: String = """
            {"name": "vivhite-tracker-1.1.92.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.92.apk"},
            {"name": "version.json", "browser_download_url": "https://example.com/version.json"}
        """
    ) = """{"tag_name": "$tagName", "body": "$body", "assets": [$assets]}"""

    @Test
    fun `parseLatestRelease 解析完整 assets`() {
        val raw = UpdateDecider.parseLatestRelease(releaseJson())!!
        assertEquals("v1.1.2", raw.tagName)
        assertTrue(raw.changelog.contains("What's Changed"))
        assertEquals("https://example.com/vivhite-tracker-1.1.92.apk", raw.apkUrl)
        assertEquals("vivhite-tracker-1.1.92.apk", raw.apkFileName)
        assertEquals("https://example.com/version.json", raw.versionJsonUrl)
    }

    @Test
    fun `parseLatestRelease 缺 tag_name 返回 null`() {
        assertNull(UpdateDecider.parseLatestRelease("""{"body": "x", "assets": []}"""))
    }

    @Test
    fun `parseLatestRelease 非法 JSON 返回 null`() {
        assertNull(UpdateDecider.parseLatestRelease("not json"))
        assertNull(UpdateDecider.parseLatestRelease(""))
    }

    @Test
    fun `parseLatestRelease 无匹配 asset 时字段为 null`() {
        val raw = UpdateDecider.parseLatestRelease(
            releaseJson(assets = """{"name": "app-release.apk", "browser_download_url": "https://example.com/app-release.apk"}""")
        )!!
        assertNull(raw.apkUrl)
        assertNull(raw.apkFileName)
        assertNull(raw.versionJsonUrl)
    }

    @Test
    fun `parseVersionJson 有效与无效输入`() {
        assertEquals(92 to "1.1.92", UpdateDecider.parseVersionJson("""{"versionCode":92,"versionName":"1.1.92"}"""))
        assertNull(UpdateDecider.parseVersionJson("""{"versionName":"1.1.92"}"""))
        assertNull(UpdateDecider.parseVersionJson("""{"versionCode":92}"""))
        assertNull(UpdateDecider.parseVersionJson("not json"))
    }

    private fun raw(
        apkUrl: String? = "https://example.com/vivhite-tracker-1.1.92.apk"
    ) = UpdateDecider.RawRelease(
        tagName = "v1.1.2",
        changelog = "changelog",
        apkUrl = apkUrl,
        apkFileName = apkUrl?.substringAfterLast('/'),
        versionJsonUrl = null
    )

    @Test
    fun `decide 远端更新时返回 UpdateAvailable`() {
        val state = UpdateDecider.decide(91, "1.1.91", 92 to "1.1.92", raw())
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals(92, info.versionCode)
        assertEquals("1.1.92", info.versionName)
        assertEquals("https://example.com/vivhite-tracker-1.1.92.apk", info.apkUrl)
        assertEquals("changelog", info.changelog)
        assertEquals("v1.1.2", info.tagName)
    }

    @Test
    fun `decide 远端相同或更旧时 UpToDate`() {
        assertEquals(UpdateDecider.UpdateState.UpToDate, UpdateDecider.decide(92, "1.1.92", 92 to "1.1.92", raw()))
        assertEquals(UpdateDecider.UpdateState.UpToDate, UpdateDecider.decide(93, "1.1.93", 92 to "1.1.92", raw()))
    }

    @Test
    fun `decide 远端版本未知时 Error`() {
        val state = UpdateDecider.decide(91, "1.1.91", null, raw())
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `decide 有更新但缺 APK 地址时 Error`() {
        val state = UpdateDecider.decide(91, "1.1.91", 92 to "1.1.92", raw(apkUrl = null))
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    // ---------- versionCode 撞车场景：fallback 到 versionName 语义比较 ----------

    @Test
    fun `versionCode 相等 本地 1_2_0+6 远端 1_3_0 远端更新`() {
        // 用户场景：本地开发包 versionCode=110 versionName="1.2.0+6"，
        // 远端 v1.3.0 release versionCode=110 versionName="1.3.0"。
        // 单比 versionCode 误判 UpToDate。fallback 到语义版本比较。
        val state = UpdateDecider.decide(
            localVersionCode = 110, localVersionName = "1.2.0+6",
            remoteVersion = 110 to "1.3.0", raw = raw()
        )
        assertTrue("应报远端更新", state is UpdateDecider.UpdateState.UpdateAvailable)
    }

    @Test
    fun `versionCode 相等 本地 1_3_0+6 远端 1_3_0 UpToDate`() {
        // versionCode 撞车 + 三段相等：本地 build 时最近的 tag 是 v1.2.0（v1.3.0 还没打），
        // 远端 release 是 v1.3.0 tag 在 HEAD 上。两者可能指向同一 commit 数，
        // 装的就是同一个东西，无需更新（语义上 +N 是 git describe 的相对值不可直接比较）。
        val state = UpdateDecider.decide(
            localVersionCode = 110, localVersionName = "1.3.0+6",
            remoteVersion = 110 to "1.3.0", raw = raw()
        )
        assertEquals(UpdateDecider.UpdateState.UpToDate, state)
    }

    @Test
    fun `versionCode 相等 本地 1_3_0 远端 1_3_0 完全相同 UpToDate`() {
        val state = UpdateDecider.decide(
            localVersionCode = 110, localVersionName = "1.3.0",
            remoteVersion = 110 to "1.3.0", raw = raw()
        )
        assertEquals(UpdateDecider.UpdateState.UpToDate, state)
    }

    @Test
    fun `versionCode 相等 本地 1_2_0+6 远端 1_2_0+6 完全相同 UpToDate`() {
        val state = UpdateDecider.decide(
            localVersionCode = 105, localVersionName = "1.2.0+6",
            remoteVersion = 105 to "1.2.0+6", raw = raw()
        )
        assertEquals(UpdateDecider.UpdateState.UpToDate, state)
    }

    @Test
    fun `versionCode 相等 versionName 解析失败 fallback UpToDate`() {
        // 未来 versionName 格式变了（解析失败）→ 不误报，宁可漏报
        val state = UpdateDecider.decide(
            localVersionCode = 110, localVersionName = "weird-format",
            remoteVersion = 110 to "1.3.0", raw = raw()
        )
        assertEquals(UpdateDecider.UpdateState.UpToDate, state)
    }
}
