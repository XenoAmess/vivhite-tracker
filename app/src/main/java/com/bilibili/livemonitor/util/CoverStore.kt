package com.bilibili.livemonitor.util

import android.content.Context
import com.bilibili.livemonitor.api.HttpClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.security.MessageDigest

/**
 * 直播封面收藏存储（filesDir/covers/）：
 * - **去重**：文件名 = cover URL 的 sha256，同一封面重复开播零重复下载
 * - **原图**：流式写原始字节，不采样不重编码
 * - **全保留**：不裁剪不清理（用户明确要求）
 * 读取侧（列表缩略图）按控件尺寸采样解码，不影响存储原图。
 */
open class CoverStore {

    // internal seam：单测注入假下载（byte[] 原样返回）
    internal open var fetcher: suspend (url: String) -> ByteArray? = { url ->
        runCatching {
            val conn = HttpClient.open(url, timeoutMs = 5000, referer = "https://live.bilibili.com/")
            conn.inputStream.use { it.readBytes() }.also { conn.disconnect() }
        }.getOrNull()
    }

    /** 已存在直接返回路径（去重短路）；否则下载原图落盘 */
    open suspend fun acquire(context: Context, coverUrl: String): String? = withContext(Dispatchers.IO) {
        val file = fileFor(context, coverUrl)
        if (file.exists() && file.length() > 0) {
            return@withContext file.absolutePath
        }
        val bytes = fetcher(coverUrl) ?: return@withContext null
        file.parentFile?.mkdirs()
        file.writeBytes(bytes)
        file.absolutePath
    }

    /** 去重后的落地文件（URL sha256 命名） */
    fun fileFor(context: Context, coverUrl: String): File =
        File(File(context.filesDir, "covers"), sha256Hex(coverUrl) + ".jpg")

    private fun sha256Hex(s: String): String {
        val digest = MessageDigest.getInstance("SHA-256").digest(s.toByteArray(Charsets.UTF_8))
        return digest.joinToString("") { "%02x".format(it) }
    }
}
