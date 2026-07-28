package com.bilibili.livemonitor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BilibiliActivityApi JSON 解析测试（不触网络）。
 * Robolectric：AppLogger 内部用 android.util.Log，纯 JVM 会报 not mocked。
 */
@RunWith(RobolectricTestRunner::class)
class BilibiliActivityApiTest {

    private val api = BilibiliActivityApi()

    // ---------- parseVideoList ----------

    @Test
    fun `parseVideoList 有效列表返回第一条`() {
        val json = """{"code":0,"data":{"list":{"vlist":[
            {"aid":100,"title":"新视频","pic":"x","author":"白绮","created":123},
            {"aid":99,"title":"旧视频","pic":"y","author":"白绮","created":122}
        ]}}}"""
        val result = api.parseVideoList(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val video = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals(100L, video.aid)
        assertEquals("新视频", video.title)
    }

    @Test
    fun `parseVideoList 空列表返回 NoData`() {
        val json = """{"code":0,"data":{"list":{"vlist":[]}}}"""
        assertEquals(
            BilibiliActivityApi.ActivityResult.NoData,
            api.parseVideoList(json)
        )
    }

    @Test
    fun `parseVideoList code 非 0 返回 Err`() {
        val json = """{"code":-352,"message":"风控"}"""
        val result = api.parseVideoList(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseVideoList 非法 JSON 返回 Err`() {
        assertTrue(api.parseVideoList("not json") is BilibiliActivityApi.ActivityResult.Err)
    }

    // ---------- parsePinnedVideo ----------

    @Test
    fun `parsePinnedVideo 有效置顶返回视频`() {
        val json = """{"code":0,"data":{"arc":{"aid":200,"title":"置顶视频","pic":"x"}}}"""
        val result = api.parsePinnedVideo(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val video = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals(200L, video.aid)
        assertEquals("置顶视频", video.title)
    }

    @Test
    fun `parsePinnedVideo 无置顶返回 NoData`() {
        val json = """{"code":0,"data":{}}"""
        assertEquals(
            BilibiliActivityApi.ActivityResult.NoData,
            api.parsePinnedVideo(json)
        )
    }

    @Test
    fun `parsePinnedVideo code 非 0 返回 Err`() {
        val json = """{"code":-404,"message":"not found"}"""
        assertTrue(api.parsePinnedVideo(json) is BilibiliActivityApi.ActivityResult.Err)
    }

    // ---------- parseDynamicFeed ----------

    @Test
    fun `parseDynamicFeed 有效动态返回第一条`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"dyn124","modules":{"module_dynamic":{"desc":{"text":"今天直播了"}}}},
            {"id_str":"dyn123","modules":{"module_dynamic":{"desc":{"text":"昨天"}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val dynamic = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("dyn124", dynamic.id)
        assertEquals("今天直播了", dynamic.displayText)
    }

    @Test
    fun `parseDynamicFeed 空列表返回 NoData`() {
        val json = """{"code":0,"data":{"items":[]}}"""
        assertEquals(
            BilibiliActivityApi.ActivityResult.NoData,
            api.parseDynamicFeed(json)
        )
    }

    @Test
    fun `parseDynamicFeed code 非 0 返回 Err`() {
        val json = """{"code":-352,"message":"风控"}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicFeed 无 desc 取 major title`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"dyn125","modules":{"module_dynamic":{"major":{"title":"新视频投稿"}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val dynamic = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("dyn125", dynamic.id)
        assertEquals("新视频投稿", dynamic.displayText)
    }

    // ---------- MONITOR_MID 守护 ----------

    @Test
    fun `MONITOR_MID 是白绮的 UID`() {
        // 守护硬编码值不被意外修改
        assertEquals(251990176L, BilibiliActivityApi.MONITOR_MID)
    }
}
