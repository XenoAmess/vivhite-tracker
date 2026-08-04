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
    fun `parseDynamicFeed 含 LIVE_RCMD 预告 解析开播时间`() {
        // live_start_time 毫秒时间戳；与一条普通图文动态同页
        val json = """{"code":0,"data":{"items":[
            {"id_str":"live123","type":"DYNAMIC_TYPE_LIVE_RCMD",
             "modules":{"module_author":{"is_top":false,"pub_ts":1785260000},
                        "module_dynamic":{"live_rcmd":{
                          "title":"今晚见","content":"直播时间：20:00",
                          "live_start_time":1754300000000}}}} ,
            {"id_str":"896","type":"DYNAMIC_TYPE_DRAW",
             "modules":{"module_author":{"is_top":false,"pub_ts":1785240803},
                        "module_desc":{"desc":{"text":"测试动态文本"}},
                        "module_dynamic":{"dyn_draw":{"id":403437691,"items":[]}}}}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("896", info.id) // 最新动态仍是图文
        assertNotNull("应解析出直播预告", info.liveRcmd)
        val rcmd = info.liveRcmd!!
        assertEquals("live123", rcmd.dynamicId)
        assertEquals(1754300000000L, rcmd.liveStartMs)
        assertEquals("今晚见", rcmd.title)
        assertEquals("直播时间：20:00", rcmd.contentText)
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

    // ---------- 2026-08-02 线上实锤修复：modules 为 JSONArray + 置顶占位 ----------

    @Test
    fun `parseDynamicItem modules 为 JSONArray 时正确解析`() {
        // 线上 desktop 端点实际返回 modules 为 JSONArray（单键对象列表），
        // 旧代码 optJSONObject("modules") 得 null → is_top/文案/avItem 全丢
        val json = """{"code":0,"data":{"items":[
            {"id_str":"1231913654110126086","type":"DYNAMIC_TYPE_DRAW",
             "modules":[
               {"module_author":{"is_top":false,"pub_ts":1785699240}},
               {"module_desc":{"desc":{"text":"今天的新动态"}}},
               {"module_dynamic":{"dyn_draw":{"id":1,"items":[]}}}
             ]}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("1231913654110126086", info.id)
        assertEquals("今天的新动态", info.displayText)
        assertFalse(info.isTop)
        assertEquals(1785699240L, info.pubTs)
    }

    @Test
    fun `parseDynamicItem module_desc 直挂 text 的线上真实结构`() {
        // 2026-08-02 线上实测：module_desc = {"rich_text_nodes":[...], "text":"分享图片"}
        // text 直挂在 module_desc 上（不是嵌套 desc.text），旧代码取不到导致通知无缩略
        val json = """{"code":0,"data":{"items":[
            {"id_str":"1231957583013609473","type":"DYNAMIC_TYPE_DRAW",
             "modules":[
               {"module_author":{"is_top":false,"pub_ts":1785675877}},
               {"module_desc":{"rich_text_nodes":[{"orig_text":"分享图片","text":"分享图片","type":"RICH_TEXT_NODE_TYPE_TEXT"}],"text":"分享图片"}},
               {"module_dynamic":{"dyn_draw":{"id":404053490,"items":[]}}}
             ]}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        assertEquals("分享图片", (result as BilibiliActivityApi.ActivityResult.Ok).data.displayText)
    }

    @Test
    fun `parseDynamicItem JSONArray 形态的 AV 模块正确解析`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"456","type":"DYNAMIC_TYPE_AV",
             "modules":[
               {"module_author":{"is_top":false,"pub_ts":100}},
               {"module_dynamic":{"dyn_archive":{
                 "aid":"116853292667144","bvid":"BV1FiTW6HE8k",
                 "title":"数组形态视频","duration_text":"04:18",
                 "cover":"http://i0.hdslb.com/cover.jpg",
                 "stat":{"play":3546,"like":222}}}}
             ]}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val av = (result as BilibiliActivityApi.ActivityResult.Ok).data.avItem
        assertNotNull("JSONArray 形态 avItem 不得丢失", av)
        assertEquals("数组形态视频", av!!.title)
        assertEquals(116853292667144L, av.aid)
        assertEquals(3546L, av.playCount)
        assertEquals("最新非置顶视频必须单独保留", av, (result as BilibiliActivityApi.ActivityResult.Ok).data.latestAvItem)
    }

    @Test
    fun `parseDynamicFeed 置顶在首位时取第一条非置顶`() {
        // 2026-08-02 线上实锤：置顶动态（2月旧内容）恒居 items[0]，
        // 旧代码只取 items[0] → last_dynamic_id 永远是置顶 → 新动态全漏
        val json = """{"code":0,"data":{"items":[
            {"id_str":"896036023158439940","type":"DYNAMIC_TYPE_DRAW",
             "modules":[{"module_author":{"is_top":true,"pub_ts":1770273420}}]},
            {"id_str":"1231913654110126086","type":"DYNAMIC_TYPE_DRAW",
             "modules":[{"module_author":{"is_top":false,"pub_ts":1785699240}},
                        {"module_desc":{"desc":{"text":"今天的新动态"}}}]}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("必须跳过置顶取到新动态", "1231913654110126086", info.id)
        assertEquals("今天的新动态", info.displayText)
        assertFalse(info.isTop)
    }

    @Test
    fun `parseDynamicFeed 保留置顶视频同时返回最新非置顶动态`() {
        val json = """{"code":0,"data":{"items":[
            {"id_str":"pinned","type":"DYNAMIC_TYPE_AV",
             "modules":[
                {"module_author":{"is_top":true,"pub_ts":100}},
                {"module_dynamic":{"dyn_archive":{"aid":"500","bvid":"BVpin","title":"置顶视频","duration_text":"01:00","cover":"","stat":{"play":0,"like":0}}}}
             ]},
            {"id_str":"latest","type":"DYNAMIC_TYPE_DRAW",
             "modules":[
                {"module_author":{"is_top":false,"pub_ts":200}},
                {"module_desc":{"text":"刚发的新动态"}}
             ]}
        ]}}"""

        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        val info = (result as BilibiliActivityApi.ActivityResult.Ok).data
        assertEquals("latest", info.id)
        assertFalse(info.isTop)
        assertNotNull("置顶视频必须单独保留", info.pinnedAvItem)
        assertEquals(500L, info.pinnedAvItem!!.aid)
        assertEquals("置顶视频", info.pinnedAvItem!!.title)
    }

    @Test
    fun `parseDynamicFeed 全是置顶时回退第0条保证基线可落`() {
        // 边界：用户只置顶不发新内容，仍要返回数据（否则永远 Err 重试空转）
        val json = """{"code":0,"data":{"items":[
            {"id_str":"111","type":"DYNAMIC_TYPE_DRAW",
             "modules":[{"module_author":{"is_top":true,"pub_ts":100}}]}
        ]}}"""
        val result = api.parseDynamicFeed(json)
        assertTrue(result is BilibiliActivityApi.ActivityResult.Ok)
        assertTrue((result as BilibiliActivityApi.ActivityResult.Ok).data.isTop)
    }
}
