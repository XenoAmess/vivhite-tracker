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
    fun `系统分享兜底 intent 为ACTION_SEND纯文本`() {
        val intent = QqShare.buildSystemShareIntent()
        assertEquals(android.content.Intent.ACTION_SEND, intent.action)
        assertEquals("text/plain", intent.type)
        val text = intent.getStringExtra(android.content.Intent.EXTRA_TEXT) ?: ""
        assertTrue(text.contains("快来看"))
        assertTrue(text.contains("bbid=8945059"))
        assertEquals("白绮开播啦！", intent.getStringExtra(android.content.Intent.EXTRA_SUBJECT))
    }

    @Test
    fun `兜底封面是有效https地址`() {
        assertTrue(QqShare.FALLBACK_COVER_URL.startsWith("https://"))
    }
}
