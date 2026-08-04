package com.bilibili.livemonitor.util

import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * ApkPatcher 补丁格式处理：
 * - "ZiPat1" → ApkDiffPatch（native，Robolectric 无 native → 必须抛受控 Exception 而非 Error）
 * - 未知格式 → IllegalArgumentException
 * 任何失败都必须以普通 Exception 形式冒出，供上层回退全量下载。
 * （真机打补丁路径由 IncrementalUpdateInstrumentedTest 用预生成 ZiPat1 夹具覆盖）
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
    fun `ZiPat1补丁在无native环境抛受控异常而不是Error`() {
        // Robolectric 加载不到 libapkpatch.so；必须把 UnsatisfiedLinkError 包成普通 Exception，
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
