package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.net.URL
import javax.net.ssl.HttpsURLConnection

/**
 * 分享配图加载：直播间封面下载与 FileProvider 授权 uri。
 * 项目不引图片库（coil/glide），HttpsURLConnection 直连 B 站 CDN。
 *
 * 文件落在 cacheDir/shared/（file_paths.xml 已声明 cache-path），
 * 通过 FileProvider 授权给分享目标读取（ACTION_SEND EXTRA_STREAM）。
 */
open class ShareImageLoader {

    /** 下载图片到分享缓存目录，返回文件；失败返回 null（调用方回退纯文本分享） */
    open fun download(context: Context, url: String, fileName: String): File? {
        return try {
            val dir = sharedDir(context)
            val file = File(dir, fileName)
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://live.bilibili.com/")
                connectTimeout = 5000
                readTimeout = 8000
            }
            connection.inputStream.use { input ->
                file.outputStream().use { input.copyTo(it) }
            }
            connection.disconnect()
            if (file.length() > 0) file else null.also { file.delete() }
        } catch (e: Exception) {
            AppLogger.w(TAG, "download share image failed: $url", e)
            null
        }
    }

    /** 下载并解码为 Bitmap（长宣传图合成用）；失败返回 null（渲染器用占位块） */
    open fun downloadBitmap(url: String): Bitmap? {
        return try {
            val connection = URL(url).openConnection() as HttpsURLConnection
            connection.apply {
                requestMethod = "GET"
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Referer", "https://live.bilibili.com/")
                connectTimeout = 5000
                readTimeout = 8000
            }
            val bitmap = connection.inputStream.use { BitmapFactory.decodeStream(it) }
            connection.disconnect()
            bitmap
        } catch (e: Exception) {
            AppLogger.w(TAG, "download share bitmap failed: $url", e)
            null
        }
    }

    /** bitmap 写入分享缓存目录（长宣传图落盘） */
    open fun save(context: Context, bitmap: Bitmap, fileName: String): File? {
        return try {
            val file = File(sharedDir(context), fileName)
            file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, 100, it) }
            file
        } catch (e: Exception) {
            AppLogger.e(TAG, "save share image failed", e)
            null
        }
    }

    /** FileProvider 授权 uri（对应 file_paths.xml 的 cache-path name="shared"） */
    fun shareableUri(context: Context, file: File): Uri =
        FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)

    private fun sharedDir(context: Context): File =
        File(context.cacheDir, "shared").apply { mkdirs() }

    companion object {
        private const val TAG = "ShareImageLoader"
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
