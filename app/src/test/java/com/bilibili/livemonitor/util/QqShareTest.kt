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
        val intent = QqShare.buildQqShareIntent(
            coverUrl = "https://i0.hdslb.com/cover.jpg",
            qqPackage = "com.tencent.mobileqq"
        )
        val url = intent.dataString ?: ""
        assertTrue(url.startsWith("mqqapi://share/to_friend"))
        assertTrue(url.contains("file_type=news"))
        // 参数应 URL 编码
        val decoded = URLDecoder.decode(url, "UTF-8")
        assertTrue(decoded.contains("title=白绮开播啦！"))
        assertTrue(decoded.contains("app_name=牢白播了吗"))
        assertTrue(decoded.contains("image_url=https://i0.hdslb.com/cover.jpg"))
        assertTrue(decoded.contains("bbid=8945059"))
        assertEquals("com.tencent.mobileqq", intent.`package`)
    }

    @Test
    fun `QQ卡片intent 未指定包名时不带setPackage`() {
        val intent = QqShare.buildQqShareIntent("https://i0.hdslb.com/cover.jpg", null)
        assertEquals(null, intent.`package`)
    }

    @Test
    fun `系统分享兜底 intent 为ACTION_SEND纯文本`() {
        val intent = QqShare.buildSystemShareIntent()
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        assertTrue(text.contains("白绮开播啦"))
        assertTrue(text.contains("bbid=8945059"))
    }

    @Test
    fun `兜底封面是有效https地址`() {
        assertTrue(QqShare.FALLBACK_COVER_URL.startsWith("https://"))
    }
}
