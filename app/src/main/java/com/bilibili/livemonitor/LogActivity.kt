package com.bilibili.livemonitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.os.Bundle
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import com.bilibili.livemonitor.util.AppLogger
import com.google.android.material.button.MaterialButton

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        tvLog = findViewById(R.id.tvLog)
        val btnCopy: MaterialButton = findViewById(R.id.btnCopy)
        val btnClear: MaterialButton = findViewById(R.id.btnClear)
        val btnRefresh: MaterialButton = findViewById(R.id.btnRefresh)

        loadLog()

        btnCopy.setOnClickListener {
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("monitor_log", tvLog.text))
            Toast.makeText(this, "日志已复制", Toast.LENGTH_SHORT).show()
        }

        btnClear.setOnClickListener {
            AppLogger.clear()
            tvLog.text = ""
            Toast.makeText(this, "日志已清空", Toast.LENGTH_SHORT).show()
        }

        btnRefresh.setOnClickListener {
            loadLog()
        }
    }

    private fun loadLog() {
        val content = AppLogger.readAll()
        tvLog.text = if (content.isEmpty()) "暂无日志" else content
    }
}
