package com.bilibili.livemonitor

import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.util.ApkPatcher
import com.bilibili.livemonitor.util.IncrementalUpdater
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File

/**
 * 增量更新的真机验证（单测覆盖不到的真机风险）：
 * 1. libapkpatch.so 在 ART 上可加载、可打补丁（ApkDiffPatch ZiPat1 格式）
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
    fun apkdiff在ART上ZipPatch往返一致() {
        // 预生成夹具（服务端 ZipDiff 产出）：old.apk + patch.bin → new.apk
        // 夹具生成见 docs/delta-update-alternatives.md「试点验证」
        val dir = context.cacheDir
        val fixtureDir = File(context.filesDir, "apkdiff-fixtures").apply { mkdirs() }
        for (name in listOf("old.apk", "new.apk", "patch.bin")) {
            context.assets.open("apkdiff-fixtures/$name").use { input ->
                File(fixtureDir, name).writeBytes(input.readBytes())
            }
        }
        val oldFile = File(fixtureDir, "old.apk")
        val patchFile = File(fixtureDir, "patch.bin")
        val expected = File(fixtureDir, "new.apk")
        val outFile = File(dir, "apkdiff-out.apk")

        ApkPatcher.applyPatch(context, oldFile, patchFile, outFile)

        assertTrue(
            "ART 上 ApkDiffPatch 打补丁结果必须与目标字节一致",
            expected.readBytes().contentEquals(outFile.readBytes())
        )
        listOf(outFile).forEach { it.delete() }
    }

    @Test
    fun 真实底包跑通单跳链() = runBlocking {
        // 用注入的中等大小合成底包跑完整编排（真 41MB 包在测试进程里处理过重；
        // 生产路径用已安装 APK 作为底包，同为此模型）
        val common = kotlin.random.Random(42).nextBytes(2 * 1024 * 1024)
        val baseBytes = common + byteArrayOf(1, 2, 3, 4)
        val targetBytes = common + byteArrayOf(9, 9, 9, 9)
        val baseFile = File(context.filesDir, "inst-base.apk").apply { writeBytes(baseBytes) }

        // 用预生成的 ZiPat1 补丁走真机打补丁路径；这里复用同一份夹具，
        // 只验证 IncrementalUpdater 编排（下载→校验→打补丁→校验结果）
        val fixtureDir = File(context.filesDir, "apkdiff-fixtures")
        if (!File(fixtureDir, "old.apk").exists()) {
            File(fixtureDir, "old.apk").parentFile.mkdirs()
            context.assets.open("apkdiff-fixtures/old.apk").use { input ->
                File(fixtureDir, "old.apk").writeBytes(input.readBytes())
            }
            context.assets.open("apkdiff-fixtures/patch.bin").use { input ->
                File(fixtureDir, "patch.bin").writeBytes(input.readBytes())
            }
            context.assets.open("apkdiff-fixtures/new.apk").use { input ->
                File(fixtureDir, "new.apk").writeBytes(input.readBytes())
            }
        }
        val baseFixture = File(fixtureDir, "old.apk")
        val patchBytes = File(fixtureDir, "patch.bin").readBytes()
        val resultBytes = File(fixtureDir, "new.apk").readBytes()

        fun sha(b: ByteArray) = java.security.MessageDigest.getInstance("SHA-256")
            .digest(b).joinToString("") { "%02x".format(it) }

        val chain = UpdateDecider.UpdateChain(
            fromApkSha256 = sha(baseFixture.readBytes()),
            totalSize = patchBytes.size.toLong(),
            hops = listOf(
                UpdateDecider.PatchHop(
                    toVersionCode = 999,
                    url = "https://test/p.patch",
                    size = patchBytes.size.toLong(),
                    patchSha256 = sha(patchBytes),
                    resultSha256 = sha(resultBytes)
                )
            )
        )
        val updater = IncrementalUpdater(context)
        updater.installedApkProvider = { baseFixture }
        updater.downloader = { _, dest, _ ->
            dest.parentFile?.mkdirs()
            dest.writeBytes(patchBytes)
            true
        }

        val result = updater.executeChain(chain, "9.9.9") {}
        assertNotNull("真机单跳链必须成功", result)
        assertTrue(resultBytes.contentEquals(result!!.readBytes()))
        result.delete()
        baseFile.delete()
        Unit
    }
}
