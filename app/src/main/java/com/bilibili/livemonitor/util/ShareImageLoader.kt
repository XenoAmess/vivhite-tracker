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
        var boundsConnection: HttpsURLConnection? = null
        var decodeConnection: HttpsURLConnection? = null
        return try {
            // 直播封面可达数 MB；先只读 bounds，再按宣传图所需分辨率采样，避免一次分享
            // 就把原图完整解进堆内存。
            val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
            boundsConnection = openImageConnection(url)
            boundsConnection.inputStream.use { BitmapFactory.decodeStream(it, null, bounds) }
            if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null

            val options = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight)
            }
            decodeConnection = openImageConnection(url)
            decodeConnection.inputStream.use { BitmapFactory.decodeStream(it, null, options) }
        } catch (e: Exception) {
            AppLogger.w(TAG, "download share bitmap failed: $url", e)
            null
        } finally {
            boundsConnection?.disconnect()
            decodeConnection?.disconnect()
        }
    }

    private fun openImageConnection(url: String): HttpsURLConnection =
        (URL(url).openConnection() as HttpsURLConnection).apply {
            requestMethod = "GET"
            setRequestProperty("User-Agent", USER_AGENT)
            setRequestProperty("Referer", "https://live.bilibili.com/")
            connectTimeout = 5000
            readTimeout = 8000
        }

    private fun sampleSizeFor(width: Int, height: Int): Int {
        var sample = 1
        while (maxOf(width / sample, height / sample) > MAX_DECODED_COVER_DIMENSION) {
            sample *= 2
        }
        return sample
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
        private const val MAX_DECODED_COVER_DIMENSION = 1440
        private const val USER_AGENT = "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0.0.0 Safari/537.36"
    }
}
