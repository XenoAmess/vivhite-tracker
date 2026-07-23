package com.bilibili.livemonitor.api

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class BilibiliApiTest {

    @Test
    fun `parseApiResponse live returns Live`() {
        val json = """{"code":0,"data":{"room_id":11258892,"live_status":1}}"""
        assertEquals(BilibiliApi.LiveStatus.Live, BilibiliApi.parseApiResponse(json))
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
        assertEquals(BilibiliApi.LiveStatus.Live, BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent live_status 0 returns NotLive`() {
        val script = """var data = {"live_status": 0, "title": "x"}"""
        assertEquals(BilibiliApi.LiveStatus.NotLive, BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent status LIVE returns Live`() {
        val script = """{"status":"LIVE","room_id":11258892}"""
        assertEquals(BilibiliApi.LiveStatus.Live, BilibiliApi.parseScriptContent(script))
    }

    @Test
    fun `parseScriptContent status 1 returns Live`() {
        val script = """{"status":1}"""
        assertEquals(BilibiliApi.LiveStatus.Live, BilibiliApi.parseScriptContent(script))
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
}
