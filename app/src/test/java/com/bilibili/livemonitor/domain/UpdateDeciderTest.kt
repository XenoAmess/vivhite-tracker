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
    fun `parseVersionMeta 核心字段与非法输入`() {
        val meta = UpdateDecider.parseVersionMeta("""{"versionCode":92,"versionName":"1.1.92"}""")
        assertEquals(92, meta!!.versionCode)
        assertEquals("1.1.92", meta.versionName)
        assertNull(meta.changelog)
        assertNull(meta.apkSha256)
        assertEquals(0L, meta.apkSize)
        assertTrue(meta.chains.isEmpty())
        assertNull(UpdateDecider.parseVersionMeta("""{"versionName":"1.1.92"}"""))
        assertNull(UpdateDecider.parseVersionMeta("""{"versionCode":92}"""))
        assertNull(UpdateDecider.parseVersionMeta("not json"))
    }

    @Test
    fun `parseVersionMeta changelog 有效 缺失 空白`() {
        // 内测版尝鲜：两通道的更新说明统一来自 version.json 的 changelog 字段
        assertEquals(
            "abc1234 feat: xxx",
            UpdateDecider.parseVersionMeta(
                """{"versionCode":92,"versionName":"1.1.92","changelog":"abc1234 feat: xxx"}"""
            )!!.changelog
        )
        // 老格式 version.json（无 changelog 字段）→ null，调用方回退
        assertNull(UpdateDecider.parseVersionMeta("""{"versionCode":92,"versionName":"1.1.92"}""")!!.changelog)
        assertNull(UpdateDecider.parseVersionMeta("""{"versionCode":92,"versionName":"x","changelog":"  "}""")!!.changelog)
    }

    @Test
    fun `parseVersionMeta 解析增量更新元数据`() {
        val json = """{
            "versionCode": 155, "versionName": "1.7.0",
            "apkSha256": "newsha", "apkSize": 41000000,
            "chains": {
                "137": {
                    "fromApkSha256": "oldsha137",
                    "totalSize": 5000000,
                    "hops": [{
                        "toVersionCode": 155,
                        "url": "https://github.com/x/patch-137-to-155.bspatch",
                        "size": 5000000,
                        "patchSha256": "psha",
                        "resultSha256": "rsha"
                    }]
                },
                "132": {
                    "fromApkSha256": "oldsha132",
                    "totalSize": 9000000,
                    "hops": [
                        {"toVersionCode": 137, "url": "https://u1", "size": 4000000, "patchSha256": "p1", "resultSha256": "r1"},
                        {"toVersionCode": 155, "url": "https://u2", "size": 5000000, "patchSha256": "p2", "resultSha256": "r2"}
                    ]
                }
            }
        }"""
        val meta = UpdateDecider.parseVersionMeta(json)!!
        assertEquals("newsha", meta.apkSha256)
        assertEquals(41000000L, meta.apkSize)
        assertEquals(2, meta.chains.size)

        val c137 = meta.chains.getValue(137)
        assertEquals("oldsha137", c137.fromApkSha256)
        assertEquals(1, c137.hops.size)
        assertEquals(155, c137.hops[0].toVersionCode)
        assertEquals("psha", c137.hops[0].patchSha256)

        val c132 = meta.chains.getValue(132)
        assertEquals(2, c132.hops.size)
        assertEquals(137, c132.hops[0].toVersionCode)
        assertEquals(155, c132.hops[1].toVersionCode)
    }

    @Test
    fun `parseVersionMeta 断链剔除 不完整跳整条作废`() {
        // 某跳缺 patchSha256 → 整条链不可信，剔除（调用方回退全量）
        val json = """{
            "versionCode": 155, "versionName": "1.7.0",
            "chains": {
                "137": {
                    "fromApkSha256": "oldsha",
                    "totalSize": 100,
                    "hops": [{"toVersionCode": 155, "url": "https://u", "size": 100, "resultSha256": "r"}]
                }
            }
        }"""
        assertTrue(UpdateDecider.parseVersionMeta(json)!!.chains.isEmpty())
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
