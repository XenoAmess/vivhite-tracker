package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import io.sigpipe.jbsdiff.Diff
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * ApkPatcher 补丁格式分派：
 * - "BSDIFF40" → jbsdiff（纯 JVM，兼容存量补丁）
 * - "ZiPat1"  → ApkDiffPatch（native，Robolectric 无 native → 必须抛受控 Exception 而非 Error）
 * - 未知格式  → IllegalArgumentException
 * 任何失败都必须以普通 Exception 形式冒出，供上层回退全量下载。
 */
@RunWith(RobolectricTestRunner::class)
class ApkPatcherTest {

    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()
    private lateinit var dir: File

    @Before
    fun setUp() {
        dir = File(context.cacheDir, "apkpatcher-test").apply { mkdirs() }
    }

    private fun writeFile(name: String, bytes: ByteArray): File =
        File(dir, name).apply { writeBytes(bytes) }

    @Test
    fun `BSDIFF40补丁走jbsdiff且结果一致`() {
        val common = ByteArray(64 * 1024) { (it % 251).toByte() }
        val oldBytes = common + byteArrayOf(1, 2, 3, 4)
        val newBytes = common + byteArrayOf(9, 9, 9, 9)

        val patchOut = ByteArrayOutputStream()
        Diff.diff(oldBytes, newBytes, patchOut)
        val patchBytes = patchOut.toByteArray()
        // bsdiff4 头
        assertTrue(String(patchBytes, 0, 8, Charsets.US_ASCII).startsWith("BSDIFF40"))

        val base = writeFile("jbsdiff-base.bin", oldBytes)
        val patch = writeFile("jbsdiff.patch", patchBytes)
        val out = File(dir, "jbsdiff-out.bin")

        ApkPatcher.applyPatch(context, base, patch, out)

        assertTrue("打补丁结果必须与目标字节一致", newBytes.contentEquals(out.readBytes()))
    }

    @Test
    fun `ZiPat1补丁在无native环境抛受控异常而不是Error`() {
        // Robolectric 加载不到 libapkpatch.so；分派器必须把 UnsatisfiedLinkError 包成普通 Exception，
        // 否则上层 catch(Exception) 兜不住、直接崩溃
        val base = writeFile("zipdiff-base.bin", ByteArray(1024) { 1 })
        val patch = writeFile("zipdiff.patch", "ZiPat1&lzma-fake-patch-content".toByteArray())
        val out = File(dir, "zipdiff-out.bin")

        try {
            ApkPatcher.applyPatch(context, base, patch, out)
            org.junit.Assert.fail("应抛出受控异常")
        } catch (e: Exception) {
            assertTrue(
                "必须转成普通异常而非 Error 冒泡: ${e.javaClass.simpleName}",
                e is IllegalStateException
            )
        }
    }

    @Test
    fun `未知格式抛IllegalArgumentException`() {
        val base = writeFile("unk-base.bin", ByteArray(16))
        val patch = writeFile("unk.patch", "NOT-A-PATCH".toByteArray())
        val out = File(dir, "unk-out.bin")

        try {
            ApkPatcher.applyPatch(context, base, patch, out)
            org.junit.Assert.fail("应抛未知格式异常")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("unknown patch format"))
        }
    }

    @Test
    fun `空文件格式判定为未知`() {
        val base = writeFile("empty-base.bin", ByteArray(16))
        val patch = writeFile("empty.patch", ByteArray(0))
        val out = File(dir, "empty-out.bin")

        try {
            ApkPatcher.applyPatch(context, base, patch, out)
            org.junit.Assert.fail("空补丁应抛异常")
        } catch (e: IllegalArgumentException) {
            assertEquals(true, e.message?.contains("unknown patch format"))
        }
    }
}
