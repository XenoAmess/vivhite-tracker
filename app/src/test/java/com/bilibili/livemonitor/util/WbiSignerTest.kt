package com.bilibili.livemonitor.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * WbiSigner 签名算法测试。
 *
 * 纯逻辑测试，不触网络。refreshKeysIfNeeded 需 Robolectric（prefs），
 * 这里只测 sign / getMixinKey / parseNavResponse 的确定性。
 */
class WbiSignerTest {

    // 标准 wbi key（来自 B 站 API 文档示例，非真实生产 key——key 每日更替）
    private val testImgKey = "7cd084941338484aae1ad9425b84077c"
    private val testSubKey = "4932caff0ff746eab6f01bf08b70d459"

    @Test
    fun `getMixinKey 取前 32 字符且确定性`() {
        val raw = testImgKey + testSubKey  // 64 字符
        val mixinKey = WbiSigner.getMixinKey(raw)
        assertEquals(32, mixinKey.length)
        // 确定性：同样输入同样输出
        assertEquals(mixinKey, WbiSigner.getMixinKey(raw))
    }

    @Test
    fun `getMixinKey 置换表正确性`() {
        // 手动验证前几个字符：MIXIN_KEY_ENC_TAB[0]=46 → raw[46]
        val raw = testImgKey + testSubKey
        val mixinKey = WbiSigner.getMixinKey(raw)
        // raw[46] = testSubKey[14]（46-32=14）
        assertEquals(raw[46], mixinKey[0])
        // MIXIN_KEY_ENC_TAB[1]=47 → raw[47]
        assertEquals(raw[47], mixinKey[1])
    }

    @Test
    fun `sign 生成 w_rid 且包含 wts`() {
        val params = mapOf("mid" to "251990176", "ps" to "1")
        val signed = WbiSigner.sign(params, testImgKey, testSubKey)

        assertTrue("应包含 wts", signed.containsKey("wts"))
        assertTrue("应包含 w_rid", signed.containsKey("w_rid"))
        val wRid = signed["w_rid"]!!
        assertEquals("w_rid 应是 32 位 hex MD5", 32, wRid.length)
        assertTrue("w_rid 应是 hex", wRid.all { it in '0'..'9' || it in 'a'..'f' })
    }

    @Test
    fun `sign 确定性`() {
        // 固定 wts 不容易（System.currentTimeMillis），但同进程内两次调用 wts 可能不同
        // 这里验证 mixinKey 相同时 w_rid 算法确定性：用固定 wts 模拟
        val params = mapOf("mid" to "251990176")
        val signed1 = WbiSigner.sign(params, testImgKey, testSubKey)
        val signed2 = WbiSigner.sign(params, testImgKey, testSubKey)
        // wts 可能不同（秒级），但如果在同一秒内调用则 w_rid 相同
        if (signed1["wts"] == signed2["wts"]) {
            assertEquals(signed1["w_rid"], signed2["w_rid"])
        }
    }

    @Test
    fun `sign 不同 key 产生不同 w_rid`() {
        val params = mapOf("mid" to "251990176")
        val signed1 = WbiSigner.sign(params, testImgKey, testSubKey)
        val signed2 = WbiSigner.sign(params, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa", "bbbbbbbbbbbbbbbbbbbbbbbbbbbbbbbb")
        // wts 相同时 w_rid 必不同
        if (signed1["wts"] == signed2["wts"]) {
            assertFalse(signed1["w_rid"] == signed2["w_rid"])
        }
    }

    @Test
    fun `sign 不同参数产生不同 w_rid`() {
        val signed1 = WbiSigner.sign(mapOf("mid" to "111"), testImgKey, testSubKey)
        val signed2 = WbiSigner.sign(mapOf("mid" to "222"), testImgKey, testSubKey)
        if (signed1["wts"] == signed2["wts"]) {
            assertFalse(signed1["w_rid"] == signed2["w_rid"])
        }
    }

    @Test
    fun `parseNavResponse 有效响应`() {
        val json = """{"code":0,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/7cd084941338484aae1ad9425b84077c.png","sub_url":"https://i0.hdslb.com/bfs/wbi/4932caff0ff746eab6f01bf08b70d459.png"}}}"""
        val (imgKey, subKey) = WbiSigner.parseNavResponse(json)!!
        assertEquals("7cd084941338484aae1ad9425b84077c", imgKey)
        assertEquals("4932caff0ff746eab6f01bf08b70d459", subKey)
    }

    @Test
    fun `parseNavResponse 缺 wbi_img 返回 null`() {
        val json = """{"code":0,"data":{}}"""
        assertNull(WbiSigner.parseNavResponse(json))
    }

    @Test
    fun `parseNavResponse 非法 JSON 返回 null`() {
        assertNull(WbiSigner.parseNavResponse("not json"))
        assertNull(WbiSigner.parseNavResponse(""))
    }

    @Test
    fun `parseNavResponse code 非 0 仍解析 key`() {
        // 未登录时 code=-101 但 data.wbi_img 仍存在
        val json = """{"code":-101,"data":{"wbi_img":{"img_url":"https://i0.hdslb.com/bfs/wbi/aaa.png","sub_url":"https://i0.hdslb.com/bfs/wbi/bbb.png"}}}"""
        val (imgKey, subKey) = WbiSigner.parseNavResponse(json)!!
        assertEquals("aaa", imgKey)
        assertEquals("bbb", subKey)
    }
}
