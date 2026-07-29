package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * VersionName 解析与比较（纯函数，无 Android 依赖）。
 */
class VersionNameTest {

    // ---------- parse ----------

    @Test
    fun `parse 1_3_0`() {
        val v = VersionName.parse("1.3.0")!!
        assertEquals(1, v.major)
        assertEquals(3, v.minor)
        assertEquals(0, v.patch)
        assertNull(v.suffix)
    }

    @Test
    fun `parse 1_2_0+6 带 suffix`() {
        val v = VersionName.parse("1.2.0+6")!!
        assertEquals(1, v.major)
        assertEquals(2, v.minor)
        assertEquals(0, v.patch)
        assertEquals(6, v.suffix)
    }

    @Test
    fun `parse 0_0_0+110 无 tag 模式`() {
        val v = VersionName.parse("0.0.0+110")!!
        assertEquals(0, v.major)
        assertEquals(0, v.minor)
        assertEquals(0, v.patch)
        assertEquals(110, v.suffix)
    }

    @Test
    fun `parse 旧版 1_1_97 无 tag 无 suffix`() {
        // 旧版无 tag 时的形态：patch 大于 9
        val v = VersionName.parse("1.1.97")!!
        assertEquals(1, v.major)
        assertEquals(1, v.minor)
        assertEquals(97, v.patch)
        assertNull(v.suffix)
    }

    @Test
    fun `parse 非法字符串返回 null`() {
        assertNull(VersionName.parse(""))
        assertNull(VersionName.parse("not a version"))
        assertNull(VersionName.parse("1.3"))           // 只有两段
        assertNull(VersionName.parse("1.3.0.5"))       // 四段
        assertNull(VersionName.parse("v1.3.0"))        // 带 v 前缀
        assertNull(VersionName.parse("1.3.0+6+10"))    // 两个 suffix
    }

    @Test
    fun `parse 带空格 trim`() {
        val v = VersionName.parse("  1.3.0+6  ")!!
        assertEquals(1, v.major)
        assertEquals(6, v.suffix)
    }

    // ---------- isRemoteNewer ----------

    private fun v(major: Int, minor: Int, patch: Int, suffix: Int? = null) =
        VersionName.Version(major, minor, patch, suffix)

    @Test
    fun `MAJOR 更大 新`() {
        assertTrue(VersionName.isRemoteNewer(v(2, 0, 0), v(1, 99, 99)))
    }

    @Test
    fun `MAJOR 相等 MINOR 更大 新`() {
        assertTrue(VersionName.isRemoteNewer(v(1, 4, 0), v(1, 3, 99)))
    }

    @Test
    fun `MAJOR MINOR 相等 PATCH 更大 新`() {
        assertTrue(VersionName.isRemoteNewer(v(1, 3, 5), v(1, 3, 4)))
    }

    @Test
    fun `三段全等 SUFFIX 更大 新`() {
        // 三段相等时按理 UpToDate。但远端 +N 大的特殊场景——同一 commit 序号下，
        // +N 大说明 git describe 看到的"比 tag 远"。这里我们按规则三段相等 UpToDate。
        assertFalse(VersionName.isRemoteNewer(v(1, 3, 0, 10), v(1, 3, 0, 6)))
    }

    /**
     * 关键场景：本地 "1.2.0+6" vs 远端 "1.3.0"。
     * 三段不等（MAJOR.MINOR），SUFFIX 不参与比较，远端新。
     */
    @Test
    fun `用户场景 本地 v1_2_0+6 远端 v1_3_0 新`() {
        val local = v(1, 2, 0, 6)
        val remote = v(1, 3, 0, null)
        assertTrue(VersionName.isRemoteNewer(remote, local))
    }

    /**
     * 关键场景：本地 "1.3.0+6" vs 远端 "1.3.0"（versionCode 撞车）。
     * 三段相等 → UpToDate。
     *
     * 实际含义：本地 build 时最近的 tag 是 v1.2.0（v1.3.0 还没打），
     * 远端 release 是后来打的 v1.3.0 tag 在 HEAD 上。两者可能指向同一 commit 数，
     * 装的就是同一个东西，无需更新。
     */
    @Test
    fun `versionCode 撞车场景 本地 v1_3_0+6 远端 v1_3_0 不新`() {
        val local = v(1, 3, 0, 6)
        val remote = v(1, 3, 0, null)
        assertFalse("同一 commit 不应报更新", VersionName.isRemoteNewer(remote, local))
    }

    @Test
    fun `完全相等 不新`() {
        assertFalse(VersionName.isRemoteNewer(v(1, 3, 0), v(1, 3, 0)))
        assertFalse(VersionName.isRemoteNewer(v(1, 3, 0, 6), v(1, 3, 0, 6)))
        // 两边都无 suffix
        assertFalse(VersionName.isRemoteNewer(v(1, 3, 0, null), v(1, 3, 0, null)))
    }

    @Test
    fun `MAJOR 更旧 不新`() {
        assertFalse(VersionName.isRemoteNewer(v(0, 99, 99), v(1, 0, 0)))
    }

    @Test
    fun `MINOR 更旧 不新`() {
        assertFalse(VersionName.isRemoteNewer(v(1, 2, 99), v(1, 3, 0)))
    }

    @Test
    fun `PATCH 更旧 不新`() {
        assertFalse(VersionName.isRemoteNewer(v(1, 3, 3), v(1, 3, 4)))
    }
}