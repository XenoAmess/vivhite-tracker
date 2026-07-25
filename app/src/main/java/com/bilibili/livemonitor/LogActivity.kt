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

        // targetSdk 35+ 强制 edge-to-edge，内容会顶到状态栏下面被遮挡；
        // 把系统栏高度加进顶部 padding（保留原有 16dp 内边距）
        val root = findViewById<android.widget.LinearLayout>(R.id.logRoot)
        val basePaddingTop = root.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, basePaddingTop + bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

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
