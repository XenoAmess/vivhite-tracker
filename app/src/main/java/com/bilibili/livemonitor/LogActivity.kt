package com.bilibili.livemonitor

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.os.Bundle
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.EditText
import android.widget.ScrollView
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.FileProvider
import androidx.lifecycle.lifecycleScope
import com.bilibili.livemonitor.util.AppLogger
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class LogActivity : AppCompatActivity() {

    private lateinit var tvLog: TextView
    private lateinit var logScrollView: ScrollView
    private var rawLog = ""
    private var selectedLevel = ""
    private var renderJob: Job? = null
    private var renderGeneration = 0L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_log)

        val toolbar: MaterialToolbar = findViewById(R.id.logToolbar)
        setSupportActionBar(toolbar)
        supportActionBar?.setDisplayHomeAsUpEnabled(true)
        toolbar.setNavigationOnClickListener { onBackPressedDispatcher.onBackPressed() }

        // targetSdk 35+ 强制 edge-to-edge，把系统栏高度补到页面根容器。
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
        logScrollView = findViewById(R.id.logScrollView)
        val filter: EditText = findViewById(R.id.etLogFilter)
        val levelSpinner: Spinner = findViewById(R.id.spinnerLogLevel)
        val btnCopy: MaterialButton = findViewById(R.id.btnCopy)
        val btnClear: MaterialButton = findViewById(R.id.btnClear)
        val btnRefresh: MaterialButton = findViewById(R.id.btnRefresh)
        val btnExport: MaterialButton = findViewById(R.id.btnExport)

        levelSpinner.adapter = ArrayAdapter(
            this,
            android.R.layout.simple_spinner_item,
            LEVEL_LABELS
        ).apply { setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item) }
        levelSpinner.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, position: Int, id: Long) {
                selectedLevel = LEVEL_CODES[position]
                renderLog(filter.text.toString())
            }

            override fun onNothingSelected(parent: AdapterView<*>?) = Unit
        }
        filter.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) = Unit
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {
                renderLog(s?.toString().orEmpty())
            }
            override fun afterTextChanged(s: Editable?) = Unit
        })

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
            AlertDialog.Builder(this)
                .setTitle("清空运行日志？")
                .setMessage("此操作无法撤销。")
                .setPositiveButton("清空") { _, _ ->
                    lifecycleScope.launch {
                        withContext(Dispatchers.IO) { AppLogger.clear() }
                        renderJob?.cancel()
                        renderGeneration++
                        rawLog = ""
                        tvLog.text = ""
                        Toast.makeText(this@LogActivity, "日志已清空", Toast.LENGTH_SHORT).show()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
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
        renderLog(findViewById<EditText>(R.id.etLogFilter).text.toString(), reload = true)
    }

    private fun renderLog(query: String, reload: Boolean = false) {
        val generation = ++renderGeneration
        val level = selectedLevel
        val currentRaw = rawLog
        renderJob?.cancel()
        renderJob = lifecycleScope.launch {
            val (loadedRaw, rendered) = withContext(Dispatchers.IO) {
                val source = if (reload || currentRaw.isEmpty()) AppLogger.readAll() else currentRaw
                source to formatLog(source, query, level)
            }
            if (generation != renderGeneration) return@launch
            rawLog = loadedRaw
            tvLog.text = rendered
            logScrollView.post { logScrollView.fullScroll(View.FOCUS_DOWN) }
        }
    }

    private fun exportLog() {
        lifecycleScope.launch {
            val logFile = withContext(Dispatchers.IO) {
                AppLogger.flush()
                AppLogger.getLogFile()?.takeIf { it.exists() }
            }
            if (logFile == null) {
                Toast.makeText(this@LogActivity, "暂无日志可导出", Toast.LENGTH_SHORT).show()
                return@launch
            }
            val uri = FileProvider.getUriForFile(
                this@LogActivity,
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
    }

    companion object {
        private const val TAIL_LINES = 2000
        private const val MAX_COPY_CHARS = 500 * 1024
        private val LEVEL_LABELS = arrayOf("全部级别", "调试 (D)", "警告 (W)", "错误 (E)")
        private val LEVEL_CODES = arrayOf("", "D", "W", "E")

        internal fun formatLog(raw: String, query: String, level: String): String {
            if (raw.isEmpty()) return "暂无日志"
            val normalizedQuery = query.trim()
            val lines = raw.lineSequence()
                .filter { level.isEmpty() || it.contains(" $level/") }
                .filter { normalizedQuery.isEmpty() || it.contains(normalizedQuery, ignoreCase = true) }
                .toList()
            if (lines.isEmpty()) return "没有匹配的日志"
            if (lines.size <= TAIL_LINES) return lines.joinToString("\n")
            val tail = lines.takeLast(TAIL_LINES).joinToString("\n")
            return "（仅显示最近 $TAIL_LINES 行，共 ${lines.size} 行；完整日志请用「导出」）\n\n$tail"
        }
    }
}
