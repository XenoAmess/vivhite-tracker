package com.bilibili.livemonitor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * BilibiliActivityApi JSON 解析测试。
 *
 * Fixture 数据来自 2026-07-29 实测 desktop feed/space 响应（简化版）。
 * Robolectric：AppLogger 内部用 android.util.Log，纯 JVM 会报 not mocked。
 */
@RunWith(RobolectricTestRunner::class)
class BilibiliActivityApiTest {

    private val api = BilibiliActivityApi()

    // ---------- parseDynamicFeed + parseDynamicItem ----------

    @Test
    fun `parseDynamicFeed DYNAMIC_TYPE_AV 含 aid bvid title`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"123","type":"DYNAMIC_TYPE_AV",
             "modules":{"module_author":{"is_top":true,"pub_ts":1785260000},
                       "module_desc":null,
                       "module_dynamic":{"dyn_archive":{
                         "aid":"116853292667144","bvid":"BV1FiTW6HE8k",
                         "title":"测试视频标题","duration_text":"04:18",
                         "cover":"http://i0.hdslb.com/cover.jpg",
                         "stat":{"play":"3546","like":"222","danmaku":"11"}}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("123", info.id)
        assertEquals("DYNAMIC_TYPE_AV", info.type)
        assertEquals(1785260000L, info.pubTs)
        assertTrue("is_top=true", info.isTop)
        assertNotNull("avItem 非空", info.avItem)
        val av = info.avItem!!
        assertEquals(116853292667144L, av.aid)
        assertEquals("BV1FiTW6HE8k", av.bvid)
        assertEquals("测试视频标题", av.title)
        assertEquals("04:18", av.durationText)
        assertEquals(3546L, av.playCount)
        assertEquals(222L, av.likeCount)
        assertEquals("displayText 为空（AV type）", "", info.displayText)
    }

    @Test
    fun `parseDynamicFeed DYNAMIC_TYPE_DRAW 含 displayText 无 avItem`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"896","type":"DYNAMIC_TYPE_DRAW",
             "modules":{"module_author":{"is_top":false,"pub_ts":1785240803},
                       "module_desc":{"desc":{"text":"测试动态文本"}},
                       "module_dynamic":{"dyn_draw":{"id":403437691,"items":[]}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("896", info.id)
        assertEquals("DYNAMIC_TYPE_DRAW", info.type)
        assertEquals("测试动态文本", info.displayText)
        assertNull("DRAW 类型无 avItem", info.avItem)
        assertFalse("is_top=false", info.isTop)
    }

    @Test
    fun `parseDynamicFeed 空 items 返回 NoData`() {
        val json = """{"code":0,"data":{"items":[]}}"""
        assertEquals(
            BilibiliActivityApi.ActivityResult.NoData,
            api.parseDynamicFeed(json)
        )
    }

    @Test
    fun `parseDynamicFeed code 非 0 返回 Err`() {
        val json = """{"code":-352,"message":"风控"}"""
        assertTrue(api.parseDynamicFeed(json) is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicFeed 缺 id_str id 返回 Err`() {
        val json = """{"code":0,"data":{"items":[{"type":"DYNAMIC_TYPE_AV","modules":{}}]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicFeed 非法 JSON 返回 Err`() {
        assertTrue(api.parseDynamicFeed("not json") is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicFeed missing data 返回 Err`() {
        val json = """{"code":0}"""
        assertTrue(api.parseDynamicFeed(json) is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicItem 极端 case 不崩`() {
        // 完全空对象
        val result = api.parseDynamicFeed("""{"code":0,"data":{"items":[{}]}}""")
        // id 缺失 → Err
        assertTrue(result is BilibiliActivityApi.ActivityResult.Err)
    }

    @Test
    fun `parseDynamicFeed is_top false 正确解析`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"1","type":"DYNAMIC_TYPE_DRAW",
             "modules":{"module_author":{"is_top":false,"pub_ts":100},
                       "module_desc":{"desc":{"text":"hi"}},
                       "module_dynamic":{"dyn_draw":{"id":1,"items":[]}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        assertFalse((result as BilibiliActivityApi.ActivityResult.Ok).data.isTop)
    }

    // ---------- MONITOR_MID 守护 ----------

    @Test
    fun `MONITOR_MID 是白绮的 UID`() {
        // 守护硬编码值不被意外修改
        assertEquals(251990176L, BilibiliActivityApi.MONITOR_MID)
    }
}