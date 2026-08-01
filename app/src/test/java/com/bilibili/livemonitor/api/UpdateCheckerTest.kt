package com.bilibili.livemonitor.api

import com.bilibili.livemonitor.domain.UpdateDecider
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class UpdateCheckerTest {

    private class FakeUpdateChecker(
        private val responses: Map<String, String?>
    ) : UpdateChecker() {
        override suspend fun httpGet(url: String): String? = responses[url]
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
        // 服务器直接拒连（连接被拒不达）
        val checker = UpdateChecker().apply { betaVersionJsonUrl = "http://127.0.0.1:1/beta/version.json" }
        val state = checker.checkBetaChannel(localVersionCode = 141, localVersionName = "1.5.1+3")
        assertTrue(state is UpdateDecider.UpdateState.Error)
        assertEquals("network error", (state as UpdateDecider.UpdateState.Error).reason)
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

        val checker = UpdateChecker().apply { latestReleaseApi = "http://127.0.0.1:$deadPort/x" }
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
}
