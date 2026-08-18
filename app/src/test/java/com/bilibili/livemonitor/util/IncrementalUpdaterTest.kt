package com.bilibili.livemonitor.util

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.domain.UpdateDecider
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.random.Random

/**
 * 增量更新编排端到端：内存 fake 下载器 + 注入 fake 打补丁（patch 字节即目标字节）。
 * 覆盖 成功链/两跳链/底包不匹配/补丁损坏/结果不匹配/下载失败 全部分支。
 * （真实 ApkDiffPatch 打补丁路径由 IncrementalUpdateInstrumentedTest 用 ZiPat1 夹具覆盖）
 */
@RunWith(RobolectricTestRunner::class)
class IncrementalUpdaterTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var updater: IncrementalUpdater
    private lateinit var baseApk: File

    // 链式版本的"发布物"：vc -> apk 字节
    private val apkByVc = mutableMapOf<Int, ByteArray>()
    // url -> patch 字节（fake 打补丁：patch 即目标）
    private val patchByUrl = mutableMapOf<String, ByteArray>()

    @Before
    fun setUp() {
        File(context.filesDir, "updates").deleteRecursively()
        updater = IncrementalUpdater(context)
        // fake 打补丁：把"补丁"（= 目标字节）原样写进输出。
        // 真实 native 打补丁在 Robolectric 跑不了，由 instrumented 夹具覆盖。
        updater.patcher = { _, patch, out -> out.writeBytes(patch.readBytes()) }
        apkByVc.clear()
        patchByUrl.clear()
        // 底包注入：fixture 文件充当"已安装 APK"（Robolectric 改不动 context.applicationInfo）
        baseApk = File(context.filesDir, "fake-base.apk")
        updater.installedApkProvider = { baseApk.takeIf { it.exists() } }
    }

    private fun makeApk(seed: Int, size: Int = 64 * 1024): ByteArray {
        // 结构化伪随机：相邻版本大量重复前缀（贴近真实 APK 版本差异形态）
        val rnd = Random(42)
        val common = rnd.nextBytes(size - 4096)
        return common + Random(seed).nextBytes(4096)
    }

    private fun addRelease(vc: Int, bytes: ByteArray) {
        apkByVc[vc] = bytes
    }

    private fun addPatch(fromVc: Int, toVc: Int): UpdateDecider.PatchHop {
        val url = "https://test/patch-$fromVc-to-$toVc.patch"
        // fake：补丁字节 = 目标 apk 字节，配合 fake patcher 原样输出
        patchByUrl[url] = apkByVc.getValue(toVc)
        return UpdateDecider.PatchHop(
            toVersionCode = toVc,
            url = url,
            size = patchByUrl.getValue(url).size.toLong(),
            patchSha256 = sha256Bytes(patchByUrl.getValue(url)),
            resultSha256 = sha256Bytes(apkByVc.getValue(toVc))
        )
    }

    private fun sha256Bytes(b: ByteArray): String {
        val d = java.security.MessageDigest.getInstance("SHA-256")
        return d.digest(b).joinToString("") { "%02x".format(it) }
    }

    private fun chainFrom(vc: Int, hops: List<UpdateDecider.PatchHop>) =
        UpdateDecider.UpdateChain(
            fromApkSha256 = sha256Bytes(apkByVc.getValue(vc)),
            totalSize = hops.sumOf { it.size },
            hops = hops
        )

    private fun installBase(vc: Int) {
        baseApk.writeBytes(apkByVc.getValue(vc))
    }

    private fun fakeDownloader(block: (String, ByteArray) -> ByteArray? = { _, b -> b }):
            suspend (String, File, (Int) -> Unit) -> Boolean = { url, dest, cb ->
        val bytes = patchByUrl[url]
        if (bytes == null) {
            false
        } else {
            cb(50); cb(100)
            val transformed = block(url, bytes)
            if (transformed == null) false else {
                dest.parentFile?.mkdirs()
                dest.writeBytes(transformed)
                true
            }
        }
    }

    @Test
    fun `单跳链成功 输出与目标APK字节一致`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101)))
        updater.downloader = fakeDownloader()

        val result = updater.executeChain(chain, "1.1.101") {}

        assertNotNull(result)
        assertTrue("产物必须与目标 APK 字节一致", apkByVc.getValue(101).contentEquals(result!!.readBytes()))
    }

    @Test
    fun `两跳链成功 中间产物逐级校验`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101)); addRelease(102, makeApk(102))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101), addPatch(101, 102)))
        updater.downloader = fakeDownloader()

        val result = updater.executeChain(chain, "1.1.102") {}

        assertNotNull(result)
        assertTrue(apkByVc.getValue(102).contentEquals(result!!.readBytes()))
        // 最终产物命名与全量下载路径一致（FileProvider updates/ 目录）
        assertEquals("vivhite-tracker-1.1.102.apk", result.name)
    }

    @Test
    fun `底包sha不匹配直接失败`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        // 链声明的底包是另一个版本的字节
        addRelease(99, makeApk(99))
        val chain = chainFrom(99, listOf(addPatch(100, 101)))
        updater.downloader = fakeDownloader()

        assertNull(updater.executeChain(chain, "1.1.101") {})
    }

    @Test
    fun `补丁内容损坏 校验失败返回null`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101)))
        updater.downloader = fakeDownloader { _, b -> b.copyOf(b.size).also { it[0] = (it[0] + 1).toByte() } }

        assertNull(updater.executeChain(chain, "1.1.101") {})
    }

    @Test
    fun `下载失败返回null`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101)))
        updater.downloader = { _, _, _ -> false }

        assertNull(updater.executeChain(chain, "1.1.101") {})
    }

    @Test
    fun `结果sha不匹配返回null 且半成品被清理`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        val hop = addPatch(100, 101).copy(resultSha256 = "0".repeat(64))
        val chain = chainFrom(100, listOf(hop))
        updater.downloader = fakeDownloader()

        assertNull(updater.executeChain(chain, "1.1.101") {})
        assertFalse(
            "失败的最终产物不得残留",
            File(context.filesDir, "updates/vivhite-tracker-1.1.101.apk").exists()
        )
        assertFalse(
            "工作目录应被清理",
            File(context.filesDir, "updates/incremental").exists()
        )
    }

    @Test
    fun `进度回调按跳大小加权`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101)); addRelease(102, makeApk(102))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101), addPatch(101, 102)))
        updater.downloader = fakeDownloader()
        val percents = mutableListOf<Int>()

        updater.executeChain(chain, "1.1.102") { percents.add(it) }

        assertTrue("进度应单调不减", percents.zipWithNext().all { (a, b) -> b >= a })
        assertEquals("最后一跳完成应到 100", 100, percents.last())
    }

    @Test
    fun `取消时清理工作目录且不发布最终APK`() = runBlocking {
        addRelease(100, makeApk(100)); addRelease(101, makeApk(101))
        installBase(100)
        val chain = chainFrom(100, listOf(addPatch(100, 101)))
        val downloadStarted = CompletableDeferred<Unit>()
        updater.downloader = { _, _, _ ->
            downloadStarted.complete(Unit)
            awaitCancellation()
        }

        val job = launch { updater.executeChain(chain, "1.1.101") {} }
        downloadStarted.await()
        job.cancelAndJoin()

        assertFalse("取消后工作目录应清理", File(context.filesDir, "updates/incremental").exists())
        assertFalse(
            "取消后不得发布最终 APK",
            File(context.filesDir, "updates/vivhite-tracker-1.1.101.apk").exists()
        )
    }

    private fun assertFalse(msg: String, value: Boolean) = org.junit.Assert.assertFalse(msg, value)
}
