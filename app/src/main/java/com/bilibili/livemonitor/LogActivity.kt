package com.bilibili.livemonitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import com.bilibili.livemonitor.util.AppLogger
import com.google.android.material.button.MaterialButton

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        // targetSdk 35+ 强制 edge-to-edge，内容会顶到状态栏下面被遮挡；
        // 把系统栏高度加进上下 padding（保留原有 16dp 内边距）
        val root = findViewById<android.widget.LinearLayout>(R.id.logRoot)
        val basePaddingLeft = root.paddingLeft
        val basePaddingTop = root.paddingTop
        val basePaddingRight = root.paddingRight
        val basePaddingBottom = root.paddingBottom
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(
                basePaddingLeft + bars.left,
                basePaddingTop + bars.top,
                basePaddingRight + bars.right,
                basePaddingBottom + bars.bottom
            )
            insets
        }

        tvLog = findViewById(R.id.tvLog)
        val btnCopy: MaterialButton = findViewById(R.id.btnCopy)
        val btnClear: MaterialButton = findViewById(R.id.btnClear)
        val btnRefresh: MaterialButton = findViewById(R.id.btnRefresh)
        val btnExport: MaterialButton = findViewById(R.id.btnExport)

        loadLog()

        btnCopy.setOnClickListener {
            val text = tvLog.text.toString()
            // 剪贴板走 binder，约 1MB 上限，超限抛 TransactionTooLargeException
            if (text.length > MAX_COPY_CHARS) {
                Toast.makeText(this, "日志过长无法复制，请使用「导出」", Toast.LENGTH_LONG).show()
            } else {
                val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                clipboard.setPrimaryClip(ClipData.newPlainText("monitor_log", text))
                Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
            }
        }

        btnClear.setOnClickListener {
            AppLogger.clear()
            tvLog.text = ""
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            loadLog()
        }

        btnExport.setOnClickListener {
            exportLog()
        }
    }

    // 全量日志塞进 TextView 会导致 UI 线程排版卡死（~1MB 几万行），
    // 只显示尾部；完整内容用「导出」分享
    private fun loadLog() {
        val content = AppLogger.readAll()
        if (content.isEmpty()) {
            tvLog.text = "暂无日志"
            return
        }
        val lines = content.lines()
        if (lines.size > TAIL_LINES) {
            val tail = lines.takeLast(TAIL_LINES).joinToString("\n")
            tvLog.text = "（仅显示最近 $TAIL_LINES 行，共 ${lines.size} 行；完整日志请用「导出」）\n\n$tail"
        } else {
            tvLog.text = content
        }
    }

    private fun exportLog() {
        val logFile = AppLogger.getLogFile()
        if (logFile == null || !logFile.exists()) {
            Toast.makeText(this, "暂无日志可导出", Toast.LENGTH_SHORT).show()
            return
        }
        val uri = FileProvider.getUriForFile(
            this,
            "$packageName.fileprovider",
            logFile
        )
        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "牢白播了吗 运行日志")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(shareIntent, "导出运行日志"))
    }

    companion object {
        private const val TAIL_LINES = 2000
        private const val MAX_COPY_CHARS = 500 * 1024
    }
}
