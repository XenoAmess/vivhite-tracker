package com.bilibili.livemonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.util.ApkPatcher
import com.bilibili.livemonitor.util.IncrementalUpdater
import io.sigpipe.jbsdiff.Diff
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.random.Random

/**
 * 增量更新的真机验证（单测覆盖不到的真机风险）：
 * 1. jbsdiff/commons-compress 在 ART 上的行为（minSdk 26 的 API 子集兼容）
 * 2. ApplicationInfo.sourceDir 已安装 APK 在真机上确实可读可算 sha256
 * 3. IncrementalUpdater 用【真 sourceDir 底包】跑通打补丁主路径
 */
@RunWith(AndroidJUnit4::class)
class IncrementalUpdateInstrumentedTest {

    private val context = InstrumentationRegistry.getInstrumentation().targetContext

    @Test
    fun installedApk可读且sha256可算() {
        val apk = ApkPatcher.installedApkFile(context)
        assertNotNull("已安装 APK 必须可读（增量打底的前提）", apk)
        val sha = ApkPatcher.sha256(apk!!)
        assertTrue("sha256 必须是 64 位 hex: $sha", sha.matches(Regex("[0-9a-f]{64}")))
    }

    @Test
    fun jbsdiff在ART上DiffPatch往返一致() {
        // 结构化字节：大量公共前缀 + 尾部差异（贴近真实 APK 的 bsdiff 场景）
        val common = Random(42).nextBytes(256 * 1024)
        val oldBytes = common + Random(1).nextBytes(8192)
        val newBytes = common + Random(2).nextBytes(8192)

        val patchOut = ByteArrayOutputStream()
        Diff.diff(oldBytes, newBytes, patchOut)

        val dir = context.cacheDir
        val oldFile = File(dir, "art-old.bin").apply { writeBytes(oldBytes) }
        val patchFile = File(dir, "art.patch").apply { writeBytes(patchOut.toByteArray()) }
        val outFile = File(dir, "art-new.bin")

        ApkPatcher.applyPatch(context, oldFile, patchFile, outFile)

        assertTrue(
            "ART 上打补丁结果必须与目标字节一致",
            newBytes.contentEquals(outFile.readBytes())
        )
        listOf(oldFile, patchFile, outFile).forEach { it.delete() }
    }

    @Test
    fun 真实底包跑通单跳链() = runBlocking {
        // 用注入的中等大小合成底包跑完整编排（Diff 只在 CI 跑，设备端只 Patch；
        // 真 41MB 包在测试进程里 Diff 会 OOM，生产路径无此操作）
        val common = Random(42).nextBytes(2 * 1024 * 1024)
        val baseBytes = common + Random(1).nextBytes(8192)
        val targetBytes = common + Random(2).nextBytes(8192)
        val baseFile = File(context.filesDir, "inst-base.apk").apply { writeBytes(baseBytes) }

        val patchOut = ByteArrayOutputStream()
        Diff.diff(baseBytes, targetBytes, patchOut)
        val patchBytes = patchOut.toByteArray()

        fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(b).joinToString("") { "%02x".format(it) }

        val chain = UpdateDecider.UpdateChain(
            fromApkSha256 = sha(baseBytes),
            totalSize = patchBytes.size.toLong(),
            hops = listOf(
                UpdateDecider.PatchHop(
                    toVersionCode = 999,
                    url = "https://test/p.bspatch",
                    size = patchBytes.size.toLong(),
                    patchSha256 = sha(patchBytes),
                    resultSha256 = sha(targetBytes)
                )
            )
        )
        val updater = IncrementalUpdater(context)
        updater.installedApkProvider = { baseFile }
        updater.downloader = { _, dest, _ ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(patchBytes)
            true
        }

        val result = updater.executeChain(chain, "9.9.9") {}
        assertNotNull("真机单跳链必须成功", result)
        assertTrue(targetBytes.contentEquals(result!!.readBytes()))
        result.delete()
        baseFile.delete()
        Unit
    }
}
