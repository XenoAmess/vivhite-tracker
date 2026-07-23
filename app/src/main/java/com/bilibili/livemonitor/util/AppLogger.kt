package com.bilibili.livemonitor.util

import android.content.Context
import android.util.Log
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.concurrent.Executors

object AppLogger {

    private const val TAG = "AppLogger"
    private const val LOG_DIR = "logs"
    private const val LOG_FILE = "monitor.log"
    private const val MAX_SIZE_BYTES = 1024 * 1024 // 1MB
    private const val TRIM_KEEP_RATIO = 0.5

    private val executor = Executors.newSingleThreadExecutor()
    private val dateFormat = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.US)

    @Volatile
    private var logFile: File? = null

    fun init(context: Context) {
        if (logFile == null) {
            synchronized(this) {
                if (logFile == null) {
                    val dir = File(context.filesDir, LOG_DIR)
                    if (!dir.exists()) dir.mkdirs()
                    logFile = File(dir, LOG_FILE)
                }
            }
        }
    }

    fun d(tag: String, message: String) {
        Log.d(tag, message)
        write("D", tag, message, null)
    }

    fun w(tag: String, message: String, throwable: Throwable? = null) {
        Log.w(tag, message, throwable)
        write("W", tag, message, throwable)
    }

    fun e(tag: String, message: String, throwable: Throwable? = null) {
        Log.e(tag, message, throwable)
        write("E", tag, message, throwable)
    }

    private fun write(level: String, tag: String, message: String, throwable: Throwable?) {
        val file = logFile ?: return
        executor.execute {
            try {
                trimIfNeeded(file)
                val timestamp = dateFormat.format(Date())
                val line = buildString {
                    append(timestamp).append(" ").append(level).append("/").append(tag).append(": ").append(message)
                    if (throwable != null) {
                        append("\n").append(Log.getStackTraceString(throwable))
                    }
                    append("\n")
                }
                file.appendText(line)
            } catch (e: Exception) {
                Log.e(TAG, "write log failed", e)
            }
        }
    }

    private fun trimIfNeeded(file: File) {
        if (!file.exists() || file.length() < MAX_SIZE_BYTES) return
        try {
            val content = file.readText()
            val keepFrom = (content.length * TRIM_KEEP_RATIO).toInt()
            val newlineAfterCut = content.indexOf('\n', keepFrom)
            val trimmed = if (newlineAfterCut > 0) content.substring(newlineAfterCut + 1) else content
            file.writeText("--- log trimmed at ${dateFormat.format(Date())} ---\n$trimmed")
        } catch (e: Exception) {
            Log.e(TAG, "trim log failed", e)
        }
    }

    fun readAll(): String {
        val file = logFile ?: return ""
        return try {
            if (file.exists()) file.readText() else ""
        } catch (e: Exception) {
            Log.e(TAG, "read log failed", e)
            ""
        }
    }

    fun clear() {
        executor.execute {
            try {
                logFile?.writeText("")
            } catch (e: Exception) {
                Log.e(TAG, "clear log failed", e)
            }
        }
    }
}
