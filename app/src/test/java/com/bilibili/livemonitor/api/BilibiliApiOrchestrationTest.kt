package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.api.BilibiliApi.LiveStatus
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * checkLiveStatus 的 API→网页兜底编排（P3）。
 * 场景：B 站 API 临时抽风时网页解析兜底；双失败时保留 API 原始错误原因。
 */
class BilibiliApiOrchestrationTest {

    class FakeApi(
        private val apiResult: LiveStatus,
        private val webResult: LiveStatus
    ) : BilibiliApi() {
        var webCalled = false
            private set

        override suspend fun checkByApi(roomId: Long): LiveStatus = apiResult

        override suspend fun checkByWebPage(roomId: Long): LiveStatus {
            webCalled = true
            return webResult
        }
    }

    @Test
    fun `API成功直接返回 不走网页兜底`() = runBlocking {
        val api = FakeApi(LiveStatus.Live, LiveStatus.NotLive)

        val result = api.checkLiveStatus(11258892L)

        assertEquals(LiveStatus.Live, result)
        assertEquals(false, api.webCalled)
    }

    @Test
    fun `API失败 网页兜底成功返回网页结果`() = runBlocking {
        // 场景：API 临时 502/限流，网页解析兜底仍给出正确状态
        val api = FakeApi(
            LiveStatus.Error("api 502"),
            LiveStatus.Live
        )

        val result = api.checkLiveStatus(11258892L)

        assertEquals(LiveStatus.Live, result)
        assertEquals(true, api.webCalled)
    }

    @Test
    fun `双失败 返回API的原始错误而不是网页的`() = runBlocking {
        // API 错误通常更结构化（超时/限流），网页错误多为解析失败；
        // 保留 API 原始原因更利于定位问题
        val api = FakeApi(
            LiveStatus.Error("api network error: timeout"),
            LiveStatus.Error("webpage parse failed")
        )

        val result = api.checkLiveStatus(11258892L)

        assertTrue(result is LiveStatus.Error)
        assertTrue(
            "应保留 API 原始错误原因",
            (result as LiveStatus.Error).reason.contains("api network error")
        )
    }

    @Test
    fun `API未开播 直接返回不兜底`() = runBlocking {
        val api = FakeApi(LiveStatus.NotLive, LiveStatus.Live)

        val result = api.checkLiveStatus(11258892L)

        assertEquals(LiveStatus.NotLive, result)
        assertEquals(false, api.webCalled)
    }
}
