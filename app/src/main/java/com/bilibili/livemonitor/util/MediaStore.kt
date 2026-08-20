package com.bilibili.livemonitor.util

import android.content.Context
import android.graphics.BitmapFactory
import com.bilibili.livemonitor.api.HttpClient
import com.bilibili.livemonitor.db.MediaSnapshotEntity
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.ByteArrayOutputStream
import java.io.File
import java.security.MessageDigest

/** Content-addressed raw storage shared by avatar history and live-room covers. */
open class MediaStore {

    data class Identity(val contentKey: String, val extension: String)
    data class StoredMedia(val contentKey: String, val fileName: String, val file: File)

    internal open var fetcher: suspend (String) -> ByteArray? = { url ->
        runCatching {
            val connection = HttpClient.open(url, timeoutMs = 5000, referer = "https://live.bilibili.com/")
            try {
                connection.inputStream.use { input ->
                    val output = ByteArrayOutputStream()
                    val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
                    var total = 0
                    while (true) {
                        val read = input.read(buffer)
                        if (read < 0) break
                        total += read
                        if (total > MAX_IMAGE_BYTES) return@runCatching null
                        output.write(buffer, 0, read)
                    }
                    output.toByteArray()
                }
            } finally {
                connection.disconnect()
            }
        }.getOrNull()
    }

    fun identityForUrl(url: String): Identity? {
        val clean = url.substringBefore('?').substringBefore('#')
        val name = clean.substringAfterLast('/')
        val match = BFS_FILE.matchEntire(name) ?: return null
        return Identity(match.groupValues[1].lowercase(), normalizeExtension(match.groupValues[2]))
    }

    open suspend fun acquire(
        context: Context,
        kind: String,
        url: String,
        isCurrent: () -> Boolean = { true }
    ): StoredMedia? =
        withContext(Dispatchers.IO) {
            if (!isCurrent()) return@withContext null
            val expected = identityForUrl(url)
            expected?.let { identity ->
                val existing = fileFor(context, kind, identity.contentKey, identity.extension)
                if (isValidImage(existing) && sha1Hex(existing) == identity.contentKey) {
                    return@withContext StoredMedia(identity.contentKey, existing.name, existing)
                }
                existing.delete()
            }

            val bytes = fetcher(url) ?: return@withContext null
            if (!isCurrent()) return@withContext null
            val actualKey = sha1Hex(bytes)
            if (expected != null && expected.contentKey != actualKey) return@withContext null
            val extension = expected?.extension ?: extensionFromUrl(url)
            val destination = fileFor(context, kind, actualKey, extension)
            if (isValidImage(destination) && sha1Hex(destination) == actualKey) {
                return@withContext StoredMedia(actualKey, destination.name, destination)
            }
            destination.delete()

            destination.parentFile?.mkdirs()
            val temp = File.createTempFile(".${destination.name}.", ".part", destination.parentFile)
            try {
                temp.outputStream().use { output ->
                    output.write(bytes)
                    output.flush()
                    (output as? java.io.FileOutputStream)?.fd?.sync()
                }
                if (!isCurrent() || !isValidImage(temp) || !AppUpdater.publishAtomically(temp, destination)) {
                    return@withContext null
                }
                StoredMedia(actualKey, destination.name, destination)
            } finally {
                temp.delete()
            }
        }

    fun fileFor(context: Context, kind: String, contentKey: String, extension: String = "jpg"): File {
        val directory = if (kind == MediaSnapshotEntity.KIND_AVATAR) "avatars" else "covers"
        return File(File(context.filesDir, directory), "$contentKey.${normalizeExtension(extension)}")
    }

    fun isValidImage(file: File): Boolean {
        if (!file.isFile || file.length() <= 0L) return false
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.absolutePath, options)
        return options.outWidth > 0 && options.outHeight > 0 &&
            options.outWidth <= MAX_DIMENSION && options.outHeight <= MAX_DIMENSION &&
            options.outWidth.toLong() * options.outHeight <= MAX_PIXELS
    }

    fun sha1Hex(file: File): String = file.inputStream().use { input ->
        val digest = MessageDigest.getInstance("SHA-1")
        val buffer = ByteArray(DEFAULT_BUFFER_SIZE)
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            digest.update(buffer, 0, read)
        }
        digest.digest().toHex()
    }

    private fun sha1Hex(bytes: ByteArray): String =
        MessageDigest.getInstance("SHA-1").digest(bytes).toHex()

    private fun ByteArray.toHex(): String = joinToString("") { "%02x".format(it) }

    private fun extensionFromUrl(url: String): String =
        normalizeExtension(url.substringBefore('?').substringAfterLast('.', "jpg"))

    private fun normalizeExtension(value: String): String = when (value.lowercase()) {
        "jpeg" -> "jpg"
        "png", "webp", "gif" -> value.lowercase()
        else -> "jpg"
    }

    companion object {
        private const val MAX_IMAGE_BYTES = 32 * 1024 * 1024
        private const val MAX_DIMENSION = 8192
        private const val MAX_PIXELS = 40_000_000L
        private val BFS_FILE = Regex("^([0-9a-fA-F]{40})\\.(jpe?g|png|webp|gif)(?:@.*)?$")
    }
}
