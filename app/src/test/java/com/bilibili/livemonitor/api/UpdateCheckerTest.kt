package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.domain.UpdateMirrors
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private class FakeUpdateChecker(
        private val responses: Map<String, String?>
    ) : UpdateChecker() {
        override suspend fun httpGet(url: String): String? = responses[url]

        // 302 回退路径的原始请求也拦截：fake 无匹配 → null（绝不打真实网络）
        override suspend fun httpGetRaw(url: String, followRedirects: Boolean): GetResult? = null
    }

    private val apiUrl = UpdateChecker.LATEST_RELEASE_API
    private val versionJsonUrl = "https://example.com/version.json"

    private fun releaseJson(assets: String) = """{
        "tag_name": "v1.1.2",
        "body": "更新日志内容",
        "assets": [$assets]
    }"""

    private val fullAssets = """
        {"name": "vivhite-tracker-1.1.92.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.92.apk"},
        {"name": "version.json", "browser_download_url": "$versionJsonUrl"}
    """

    @Test
    fun `versionJson 优先于文件名解析`() = runBlocking {
        // 文件名写的是 92，version.json 写的是 95：以 version.json 为准
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to """{"versionCode":95,"versionName":"1.1.95"}"""
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals(95, info.versionCode)
        assertEquals("1.1.95", info.versionName)
        assertEquals("https://example.com/vivhite-tracker-1.1.92.apk", info.apkUrl)
        assertEquals("更新日志内容", info.changelog)
    }

    @Test
    fun `增量元数据按本地versionCode透传到ReleaseInfo`() = runBlocking {
        // 本地 vc=91 有链 → info.chain 非空；vc=90 无链 → null（回退全量）
        val versionJson = """{
            "versionCode": 95, "versionName": "1.1.95",
            "apkSha256": "newsha", "apkSize": 41000000,
            "chains": {
                "91": {
                    "fromApkSha256": "sha91", "totalSize": 5000000,
                    "hops": [{"toVersionCode": 95, "url": "https://x/p.bspatch", "size": 5000000, "patchSha256": "p", "resultSha256": "r"}]
                }
            }
        }"""
        val checker = FakeUpdateChecker(mapOf(apiUrl to releaseJson(fullAssets), versionJsonUrl to versionJson))

        val withChain = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(withChain is UpdateDecider.UpdateState.UpdateAvailable)
        val info91 = (withChain as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals("newsha", info91.apkSha256)
        assertEquals(41000000L, info91.apkSize)
        assertEquals("sha91", info91.chain!!.fromApkSha256)
        assertEquals(1, info91.chain!!.hops.size)

        val noChain = checker.checkLatestRelease(localVersionCode = 90, localVersionName = "1.1.90")
        val info90 = (noChain as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals("newsha", info90.apkSha256)
        assertNull(info90.chain)
    }

    @Test
    fun `beta通道同样透传增量元数据`() = runBlocking {
        val betaJson = """{
            "versionCode": 96, "versionName": "1.1.96",
            "apkSha256": "betasha", "apkSize": 42000000,
            "chains": {
                "91": {
                    "fromApkSha256": "sha91", "totalSize": 3000000,
                    "hops": [{"toVersionCode": 96, "url": "https://x/bp.bspatch", "size": 3000000, "patchSha256": "p", "resultSha256": "r"}]
                }
            }
        }"""
        val checker = object : UpdateChecker() {
            override suspend fun httpGet(url: String): String? =
                if (url == betaVersionJsonUrl) betaJson else null
        }
        val state = checker.checkBetaChannel(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
        val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
        assertEquals("betasha", info.apkSha256)
        assertEquals("sha91", info.chain!!.fromApkSha256)
        assertEquals("https://x/bp.bspatch", info.chain!!.hops[0].url)
    }

    @Test
    fun `无 versionJson 时返回 Error`() = runBlocking {
        // 删掉文件名兜底后，缺 version.json 无法确定远端 versionCode，直接 Error
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(
                    """{"name": "vivhite-tracker-1.1.92.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.92.apk"}"""
                )
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `远端不更新时 UpToDate`() = runBlocking {
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to """{"versionCode":92,"versionName":"1.1.92"}"""
            )
        )
        assertEquals(
            UpdateDecider.UpdateState.UpToDate,
            checker.checkLatestRelease(localVersionCode = 92, localVersionName = "1.1.92")
        )
    }

    @Test
    fun `网络失败时 Error`() = runBlocking {
        val checker = FakeUpdateChecker(emptyMap())
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `响应非法 JSON 时 Error`() = runBlocking {
        val checker = FakeUpdateChecker(mapOf(apiUrl to "not json"))
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `旧格式 Release 无版本信息时 Error`() = runBlocking {
        // 老 Release 只有 app-release.apk，无法确定远端版本号
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(
                    """{"name": "app-release.apk", "browser_download_url": "https://example.com/app-release.apk"}"""
                )
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    @Test
    fun `versionJson 请求失败时返回 Error`() = runBlocking {
        // version.json URL 存在但下载失败（httpGet 返回 null），不再回退文件名解析
        val checker = FakeUpdateChecker(
            mapOf(
                apiUrl to releaseJson(fullAssets),
                versionJsonUrl to null
            )
        )
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
        assertTrue(state is UpdateDecider.UpdateState.Error)
    }

    // ---------- 真实端到端（JDK HttpServer + 真 HttpURLConnection） ----------

    /**
     * 本地 HttpServer 端到端：不打 fake，走真实网络栈，
     * 覆盖 httpGet/downloadApk 的连接管理、读流、错误处理本体。
     */
    private suspend fun withServer(
        handler: (com.sun.net.httpserver.HttpExchange) -> Unit,
        block: suspend (baseUrl: String) -> Unit
    ) {
        val server = com.sun.net.httpserver.HttpServer.create(
            java.net.InetSocketAddress("127.0.0.1", 0), 0
        )
        server.createContext("/") { exchange -> handler(exchange) }
        server.start()
        try {
            block("http://127.0.0.1:${server.address.port}")
        } finally {
            server.stop(0)
        }
    }

    @Test
    fun `e2e 完整release与versionJson 走真实HTTP返回UpdateAvailable`() = runBlocking {
        lateinit var base: String
        withServer({ exchange ->
            val body = when (exchange.requestURI.path) {
                "/releases/latest" -> {
                    // base 在此 lambda 首次被调用时已被外层赋值
                    releaseJson(
                        """{"name": "vivhite-tracker-1.1.95.apk", "browser_download_url": "$base/vivhite-tracker-1.1.95.apk"},
                        {"name": "version.json", "browser_download_url": "$base/version.json"}"""
                    )
                }
                "/version.json" -> """{"versionCode":95,"versionName":"1.1.95"}"""
                else -> "not found"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(if (exchange.requestURI.path == "/notfound") 404 else 200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            base = baseUrl
            val checker = UpdateChecker().apply { latestReleaseApi = "$baseUrl/releases/latest" }
            val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")

            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
            assertEquals(95, info.versionCode)
            assertEquals("1.1.95", info.versionName)
            assertEquals("$baseUrl/vivhite-tracker-1.1.95.apk", info.apkUrl)
        }
        Unit
    }

    @Test
    fun `e2e stable versionJson带changelog时覆盖release body`() = runBlocking {
        // 用户需求：更新说明两通道统一来自 version.json 的提交摘要
        lateinit var base: String
        withServer({ exchange ->
            val body = when (exchange.requestURI.path) {
                "/releases/latest" -> releaseJson(
                    """{"name": "vivhite-tracker-1.1.95.apk", "browser_download_url": "$base/vivhite-tracker-1.1.95.apk"},
                    {"name": "version.json", "browser_download_url": "$base/version.json"}"""
                )
                "/version.json" -> """{"versionCode":95,"versionName":"1.1.95","changelog":"abc1234 feat: 提交摘要"}"""
                else -> "not found"
            }
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            base = baseUrl
            val checker = UpdateChecker().apply { latestReleaseApi = "$baseUrl/releases/latest" }
            val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")

            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
            assertEquals("version.json 提交摘要必须覆盖 release body", "abc1234 feat: 提交摘要", info.changelog)
        }
        Unit
    }

    // ---------- 内测版尝鲜通道（GitHub Pages / master 最新构建） ----------

    @Test
    fun `e2e beta通道 versionCode更新 返回UpdateAvailable且带提交摘要`() = runBlocking {
        // 用户场景：点「内测版尝鲜」，主分支最新构建比本地新 → 提示更新
        withServer({ exchange ->
            val body = """{"versionCode":150,"versionName":"1.5.1+9","changelog":"abc1234 feat: 内测改动"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            val checker = UpdateChecker().apply { betaVersionJsonUrl = "$baseUrl/beta/version.json" }
            val state = checker.checkBetaChannel(localVersionCode = 141, localVersionName = "1.5.1+3")

            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
            assertEquals(150, info.versionCode)
            assertEquals("1.5.1+9", info.versionName)
            assertEquals(UpdateChecker.BETA_APK_URL, info.apkUrl)
            assertEquals(UpdateChecker.BETA_TAG_NAME, info.tagName)
            assertEquals("abc1234 feat: 内测改动", info.changelog)
        }
        Unit
    }

    @Test
    fun `e2e beta通道 versionCode不新 返回UpToDate防降级`() = runBlocking {
        // 用户场景：本地构建比内测频道还新（自己刚编的）→ 不得降级提示
        withServer({ exchange ->
            val body = """{"versionCode":141,"versionName":"1.5.1+3"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            val checker = UpdateChecker().apply { betaVersionJsonUrl = "$baseUrl/beta/version.json" }
            val state = checker.checkBetaChannel(localVersionCode = 141, localVersionName = "1.5.1+3")
            assertTrue("同版本必须 UpToDate", state is UpdateDecider.UpdateState.UpToDate)
        }
        Unit
    }

    @Test
    fun `e2e beta通道 网络失败 返回Error`() = runBlocking {
        // 主通道与 legacy 回退都拒连（连接被拒不达）
        val checker = UpdateChecker().apply {
            betaVersionJsonUrl = "http://127.0.0.1:1/beta/version.json"
            betaLegacyVersionJsonUrl = "http://127.0.0.1:1/legacy/version.json"
        }
        val state = checker.checkBetaChannel(localVersionCode = 141, localVersionName = "1.5.1+3")
        assertTrue(state is UpdateDecider.UpdateState.Error)
        assertEquals("network error", (state as UpdateDecider.UpdateState.Error).reason)
    }

    @Test
    fun `e2e beta通道 主URL失败 回退legacy Pages`() = runBlocking {
        // 主通道（beta-archive 资产）拒连 → 回退 legacy Pages URL 成功
        withServer({ exchange ->
            val body = """{"versionCode":150,"versionName":"1.5.1+9","changelog":"abc1234 feat: 内测改动"}"""
            val bytes = body.toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            val checker = UpdateChecker().apply {
                betaVersionJsonUrl = "http://127.0.0.1:1/beta/version.json"
                betaLegacyVersionJsonUrl = "$baseUrl/legacy/version.json"
            }
            val state = checker.checkBetaChannel(localVersionCode = 141, localVersionName = "1.5.1+3")
            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
            assertEquals(150, info.versionCode)
            assertEquals(UpdateChecker.BETA_APK_URL_LEGACY, info.apkUrl)
        }
        Unit
    }

    @Test
    fun `beta 新通道 URL 均可镜像加速`() {
        // beta-archive 资产是 github.com 主机 → UpdateMirrors 白名单天然生效
        assertTrue(UpdateMirrors.candidates(UpdateChecker.BETA_VERSION_JSON_URL).size > 1)
        assertTrue(UpdateMirrors.candidates(UpdateChecker.BETA_APK_URL).size > 1)
        assertEquals(
            UpdateChecker.BETA_VERSION_JSON_URL,
            UpdateMirrors.candidates(UpdateChecker.BETA_VERSION_JSON_URL).last()
        )
    }

    @Test
    fun `e2e stable api失败 回退302页拿tag再拉versionJson`() = runBlocking {
        // api.github.com 拒连 → releases/latest 302 Location 提取 tag →
        // 拼 releases/download/<tag>/version.json 拉精确版本（全程可代理 URL 形态）
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/releases/latest" -> {
                    exchange.responseHeaders.add("Location", "/releases/tag/v9.9.9")
                    exchange.sendResponseHeaders(302, -1)
                }
                "/releases/download/v9.9.9/version.json" -> {
                    val bytes = """{"versionCode":99,"versionName":"9.9.9","changelog":"abc1234 feat: 302路径"}""".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }) { baseUrl ->
            val socket = java.net.ServerSocket(0)
            val deadPort = socket.localPort
            socket.close()
            val checker = UpdateChecker().apply {
                latestReleaseApi = "http://127.0.0.1:$deadPort/api"
                latestReleasePageUrl = "$baseUrl/releases/latest"
            }
            val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            val info = (state as UpdateDecider.UpdateState.UpdateAvailable).info
            assertEquals(99, info.versionCode)
            assertEquals("9.9.9", info.versionName)
            assertEquals("$baseUrl/releases/download/v9.9.9/vivhite-tracker-9.9.9.apk", info.apkUrl)
            assertEquals("abc1234 feat: 302路径", info.changelog)
        }
        Unit
    }

    @Test
    fun `e2e stable 302页无Location时从HTML提取tag`() = runBlocking {
        // 镜像代跟重定向返回 200 HTML：从页面正则提取 tag
        withServer({ exchange ->
            when (exchange.requestURI.path) {
                "/releases/latest" -> {
                    val bytes = "<html><a href=\"/releases/tag/v9.9.8\">latest</a></html>".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                "/releases/download/v9.9.8/version.json" -> {
                    val bytes = """{"versionCode":98,"versionName":"9.9.8"}""".toByteArray()
                    exchange.sendResponseHeaders(200, bytes.size.toLong())
                    exchange.responseBody.use { it.write(bytes) }
                }
                else -> exchange.sendResponseHeaders(404, -1)
            }
        }) { baseUrl ->
            val socket = java.net.ServerSocket(0)
            val deadPort = socket.localPort
            socket.close()
            val checker = UpdateChecker().apply {
                latestReleaseApi = "http://127.0.0.1:$deadPort/api"
                latestReleasePageUrl = "$baseUrl/releases/latest"
            }
            val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
            assertTrue(state is UpdateDecider.UpdateState.UpdateAvailable)
            assertEquals("9.9.8", (state as UpdateDecider.UpdateState.UpdateAvailable).info.versionName)
        }
        Unit
    }

    @Test
    fun `e2e 服务器返回200但坏JSON Error`() = runBlocking {
        withServer({ exchange ->
            val bytes = "not json at all".toByteArray()
            exchange.sendResponseHeaders(200, bytes.size.toLong())
            exchange.responseBody.use { it.write(bytes) }
        }) { baseUrl ->
            val checker = UpdateChecker().apply { latestReleaseApi = "$baseUrl/releases/latest" }
            val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")
            assertTrue(state is UpdateDecider.UpdateState.Error)
            assertEquals("release parse error", (state as UpdateDecider.UpdateState.Error).reason)
        }
        Unit
    }

    @Test
    fun `e2e 连接被拒不达 Error network error`() = runBlocking {
        // 占一个端口然后立刻释放，大概率拿到一个未监听的端口
        val socket = java.net.ServerSocket(0)
        val deadPort = socket.localPort
        socket.close()

        val checker = UpdateChecker().apply {
            latestReleaseApi = "http://127.0.0.1:$deadPort/x"
            latestReleasePageUrl = "http://127.0.0.1:$deadPort/releases/latest"
        }
        val state = checker.checkLatestRelease(localVersionCode = 91, localVersionName = "1.1.91")

        assertTrue(state is UpdateDecider.UpdateState.Error)
        assertEquals("network error", (state as UpdateDecider.UpdateState.Error).reason)
    }

    @Test
    fun `e2e downloadApk 真实字节落盘且progress单调到100`() = runBlocking {
        // 用户场景：WiFi 下自动下载更新包，进度条前进，文件必须完整
        val payload = ByteArray(100_000) { (it % 251).toByte() }
        withServer({ exchange ->
            exchange.sendResponseHeaders(200, payload.size.toLong())
            exchange.responseBody.use { it.write(payload) }
        }) { baseUrl ->
            val dest = java.io.File.createTempFile("test-apk", ".apk")
            dest.delete() // createTempFile 已建，删掉让 downloadApk 自己建
            val progress = mutableListOf<Int>()
            val ok = UpdateChecker().downloadApk("$baseUrl/app.apk", dest) { progress.add(it) }

            assertTrue(ok)
            assertTrue("文件必须完整", dest.readBytes().contentEquals(payload))
            assertTrue("progress 必须到 100", progress.isNotEmpty() && progress.last() == 100)
            assertTrue("progress 必须单调不减", progress.zipWithNext().all { (a, b) -> b >= a })
            dest.delete()
        }
        Unit
    }

    @Test
    fun `e2e downloadApk 连接失败 返回false且半成品被清理`() = runBlocking {
        val socket = java.net.ServerSocket(0)
        val deadPort = socket.localPort
        socket.close()

        val dest = java.io.File.createTempFile("test-apk-partial", ".apk")
        dest.writeBytes(byteArrayOf(9, 9, 9)) // 预置半成品内容
        val ok = UpdateChecker().downloadApk("http://127.0.0.1:$deadPort/app.apk", dest) { _ -> }

        assertFalse(ok)
        assertFalse("失败后半成品文件必须删除", dest.exists())
    }

    @Test
    fun `e2e downloadApk 取消后不发布目标且清理partial`() = runBlocking {
        val chunk = ByteArray(8192) { 7 }
        withServer({ exchange ->
            runCatching {
                exchange.sendResponseHeaders(200, (chunk.size * 500L))
                exchange.responseBody.use { output ->
                    repeat(500) {
                        output.write(chunk)
                        output.flush()
                        Thread.sleep(2)
                    }
                }
            }
        }) { baseUrl ->
            val dir = java.nio.file.Files.createTempDirectory("cancel-apk").toFile()
            val dest = java.io.File(dir, "update.apk")
            val started = CompletableDeferred<Unit>()
            val job = launch {
                UpdateChecker().downloadApk("$baseUrl/app.apk", dest) {
                    started.complete(Unit)
                }
            }

            started.await()
            job.cancelAndJoin()

            assertFalse("取消后不得发布最终文件", dest.exists())
            assertTrue("取消后不得残留 partial", dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
            dir.deleteRecursively()
        }
        Unit
    }

    @Test
    fun `e2e downloadApk 首字节前取消会立即断开连接`() = runBlocking {
        val requestReceived = java.util.concurrent.CountDownLatch(1)
        val releaseServer = java.util.concurrent.CountDownLatch(1)
        withServer({ exchange ->
            requestReceived.countDown()
            releaseServer.await(10, java.util.concurrent.TimeUnit.SECONDS)
            runCatching {
                exchange.sendResponseHeaders(200, 1)
                exchange.responseBody.use { it.write(1) }
            }
        }) { baseUrl ->
            val dir = java.nio.file.Files.createTempDirectory("cancel-stalled-apk").toFile()
            val dest = java.io.File(dir, "update.apk")
            val job = launch(kotlinx.coroutines.Dispatchers.IO) {
                UpdateChecker().downloadApk("$baseUrl/app.apk", dest) { }
            }
            assertTrue(requestReceived.await(2, java.util.concurrent.TimeUnit.SECONDS))

            try {
                withTimeout(3_000) { job.cancelAndJoin() }
            } finally {
                releaseServer.countDown()
            }

            assertFalse(dest.exists())
            assertTrue(dir.listFiles().orEmpty().none { it.name.endsWith(".part") })
            dir.deleteRecursively()
        }
        Unit
    }
}
