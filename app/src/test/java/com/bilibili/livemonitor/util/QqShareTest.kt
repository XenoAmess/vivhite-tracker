package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.net.URLDecoder

/**
 * QQ 分享卡片与 B 站归因链接。
 */
@RunWith(RobolectricTestRunner::class)
class QqShareTest {

    @Test
    fun `分享链接按B站原生规则 含bbid归因与来源参数`() {
        val url = QqShare.buildShareUrl(ts = 1700000000000L)
        assertTrue(url.startsWith("https://live.bilibili.com/11258892"))
        assertTrue(url.contains("broadcast_type=0"))
        assertTrue(url.contains("share_source=copy_link"))
        assertTrue(url.contains("share_medium=android"))
        assertTrue("应归因到指定用户: $url", url.contains("bbid=8945059"))
        assertTrue(url.contains("ts=1700000000000"))
    }

    @Test
    fun `QQ卡片intent mqqapi格式与参数编码`() {
        // mqqapi 已弃用（新版 QQ 静默忽略），此用例仅验证链接构造不受影响
        val url = QqShare.buildShareUrl()
        assertTrue(url.contains("bbid=8945059"))
    }

    @Test
    fun `SDK分享参数 有直播标题时展示动态标题`() {
        val params = QqShare.buildSdkShareParams("https://i0.hdslb.com/cover.jpg", "失眠 无言")
        assertEquals("「失眠 无言」", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_TITLE))
        assertEquals("白绮正在直播 · 11258892", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_SUMMARY))
        assertEquals("https://i0.hdslb.com/cover.jpg", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_IMAGE_URL))
        assertEquals("牢白播了吗", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_APP_NAME))
    }

    @Test
    fun `SDK分享参数 无标题时兜底硬编码`() {
        val params = QqShare.buildSdkShareParams("https://i0.hdslb.com/cover.jpg", null)
        assertEquals("白绮开播啦！", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_TITLE))
        // 有实时状态后 summary 用准确的开播文案（旧版"白绮直播间"是不知道状态时的中性词）
        assertEquals("白绮正在直播 · 11258892", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_SUMMARY))
    }

    @Test
    fun `SDK分享参数 未开播时文案体现期待开播`() {
        // 用户需求：没开播时分享内容不能再误报"开播了"
        val params = QqShare.buildSdkShareParams("https://i0.hdslb.com/cover.jpg", "旧标题", isLive = false)
        assertEquals("白绮还没开播", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_TITLE))
        assertEquals("白绮还没开播 · 11258892", params.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_SUMMARY))
    }

    @Test
    fun `系统分享兜底 未开播时文案体现期待开播`() {
        val intent = QqShare.buildSystemShareIntent(null, isLive = false)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        assertTrue("应体现还没开播: $text", text.contains("还没开播"))
        assertTrue("应体现蹲开播意向: $text", text.contains("蹲一个开播"))
        assertFalse("不得误报开播: $text", text.contains("正在直播"))
        assertTrue("应带直播间链接: $text", text.contains("live.bilibili.com/11258892"))
    }

    @Test
    fun `系统分享兜底 intent 为ACTION_SEND纯文本`() {
        val intent = QqShare.buildSystemShareIntent("失眠 无言")
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        assertTrue(text.contains("失眠 无言"))
        assertTrue(text.contains("11258892"))
        assertTrue(text.contains("bbid=8945059"))
    }

    @Test
    fun `系统分享兜底 无标题时走硬编码`() {
        val intent = QqShare.buildSystemShareIntent(null)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        assertTrue(text.contains("快来看"))
    }

    @Test
    fun `兜底封面是有效https地址`() {
        assertTrue(QqShare.FALLBACK_COVER_URL.startsWith("https://"))
    }
}
