package com.bilibili.livemonitor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliApiTest {

    @Test
    fun `parseApiResponse live returns Live`() {
        val json = """{"code":0,"data":{"room_id":11258892,"live_status":1}}"""
        assertEquals(BilibiliApi.LiveStatus.Live(), BilibiliApi.parseApiResponse(json))
    }

    @Test
    fun `parseApiResponse live parses live_start_time`() {
        // 观播静音绑定依赖该字段：新一场开播时 live_start_time 变化
        val json = """{"code":0,"data":{"room_id":11258892,"live_status":1,"live_start_time":"2026-08-02 19:00:00"}}"""
        assertEquals(
            BilibiliApi.LiveStatus.Live("2026-08-02 19:00:00"),
            BilibiliApi.parseApiResponse(json)
        )
    }

    @Test
    fun `parseApiResponse live without live_start_time returns null field`() {
        // 兼容旧响应/异常响应：字段缺失或空白时 liveStartTime=null，不影响状态判定
        val noField = """{"code":0,"data":{"room_id":11258892,"live_status":1}}"""
        assertNull(
            (BilibiliApi.parseApiResponse(noField) as BilibiliApi.LiveStatus.Live).liveStartTime
        )
        val blank = """{"code":0,"data":{"room_id":11258892,"live_status":1,"live_start_time":""}}"""
        assertNull(
            (BilibiliApi.parseApiResponse(blank) as BilibiliApi.LiveStatus.Live).liveStartTime
        )
    }

    @Test
    fun `parseApiResponse not live returns NotLive`() {
        val json = """{"code":0,"data":{"room_id":11258892,"live_status":0}}"""
        assertEquals(BilibiliApi.LiveStatus.NotLive, BilibiliApi.parseApiResponse(json))
    }

    @Test
    fun `parseApiResponse round robin status 2 returns NotLive`() {
        // live_status=2 是轮播中，不算开播
        val json = """{"code":0,"data":{"room_id":11258892,"live_status":2}}"""
        assertEquals(BilibiliApi.LiveStatus.NotLive, BilibiliApi.parseApiResponse(json))
    }

    @Test
    fun `parseApiResponse missing data field returns Error`() {
        val json = """{"code":-404,"message":"房间不存在"}"""
        val result = BilibiliApi.parseApiResponse(json)
        assertTrue(result is BilibiliApi.LiveStatus.Error)
    }

    @Test
    fun `parseApiResponse success却缺live_status返回Error`() {
        // 缺字段不能默认成未开播，否则会覆盖上一次确定状态并吞掉重试。
        val result = BilibiliApi.parseApiResponse("""{"code":0,"data":{"room_id":11258892}}""")
        assertTrue(result is BilibiliApi.LiveStatus.Error)
    }

    @Test
    fun `parseApiResponse 非0 code即使带状态也返回Error`() {
        val result = BilibiliApi.parseApiResponse(
            """{"code":-352,"message":"风控","data":{"live_status":0}}"""
        )
        assertTrue(result is BilibiliApi.LiveStatus.Error)
    }

    @Test
    fun `parseApiResponse malformed json returns Error`() {
        val result = BilibiliApi.parseApiResponse("not a json")
        assertTrue(result is BilibiliApi.LiveStatus.Error)
    }

    @Test
    fun `parseApiResponse empty string returns Error`() {
        val result = BilibiliApi.parseApiResponse("")
        assertTrue(result is BilibiliApi.LiveStatus.Error)
    }

    @Test
    fun `parseScriptContent live_status 1 returns Live`() {
        val script = """window.__NEPTUNE_IS_MY_WAIFU__={"roomInfoRes":{"live_status":1}}"""
        assertEquals(BilibiliApi.LiveStatus.Live(), BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent live_status 0 returns NotLive`() {
        val script = """var data = {"live_status": 0, "title": "x"}"""
        assertEquals(BilibiliApi.LiveStatus.NotLive, BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent status LIVE returns Live`() {
        val script = """{"status":"LIVE","room_id":11258892}"""
        assertEquals(BilibiliApi.LiveStatus.Live(), BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent status 带空白 returns Live`() {
        val script = """{"status" : "LIVE","room_id":11258892}"""
        assertEquals(BilibiliApi.LiveStatus.Live(), BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent status 1 returns Live`() {
        val script = """{"status":1}"""
        assertEquals(BilibiliApi.LiveStatus.Live(), BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent unrelated script returns null`() {
        val script = """console.log("hello world");"""
        assertNull(BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent keyword present but no match returns null`() {
        // 包含 live_status 字样但不构成 "live_status":N 模式
        val script = """var live_status = "unknown";"""
        assertNull(BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent 未知status不猜测未开播`() {
        assertNull(BilibiliApi.parseScriptContent("""{"status":"PENDING"}"""))
    }

    @Test
    fun `parseRoomCover 有user_cover时返回URL`() {
        val json = """{"code":0,"data":{"room_id":11258892,"user_cover":"https://i0.hdslb.com/bfs/live/cover123.jpg"}}"""
        assertEquals(
            "https://i0.hdslb.com/bfs/live/cover123.jpg",
            BilibiliApi.parseRoomCover(json)
        )
    }

    @Test
    fun `parseFace 有face时返回URL`() {
        // QQ 卡片缩略图用：B站 acc/info 的 data.face（白绮方形头像）
        val json = """{"code":0,"data":{"mid":251990176,"name":"白绮","face":"https://i1.hdslb.com/bfs/face/abc123.jpg"}}"""
        assertEquals("https://i1.hdslb.com/bfs/face/abc123.jpg", BilibiliApi.parseFace(json))
    }

    @Test
    fun `parseFace 无data或坏JSON 返回null`() {
        assertNull(BilibiliApi.parseFace("""{"code":-404}"""))
        assertNull(BilibiliApi.parseFace("not json"))
    }

    @Test
    fun `parseRoomCover 无字段或非字符串时返回null`() {
        assertNull(BilibiliApi.parseRoomCover("""{"code":0,"data":{"room_id":1}}"""))
        assertNull(BilibiliApi.parseRoomCover("""{"code":0,"data":{"user_cover":""}}"""))
        assertNull(BilibiliApi.parseRoomCover("not a json"))
    }

    // ---------- parseRoomTitle ----------

    @Test
    fun `parseRoomTitle 正常标题返回`() {
        val title = BilibiliApi.parseRoomTitle("""{"code":0,"data":{"title":"失眠 无言"}}""")
        assertEquals("失眠 无言", title)
    }

    @Test
    fun `parseRoomTitle 空标题返回 null`() {
        assertNull(BilibiliApi.parseRoomTitle("""{"code":0,"data":{"title":""}}"""))
        assertNull(BilibiliApi.parseRoomTitle("""{"code":0,"data":{}}"""))
    }

    @Test
    fun `parseRoomTitle 非法 JSON 返回 null`() {
        assertNull(BilibiliApi.parseRoomTitle("not a json"))
    }
}
