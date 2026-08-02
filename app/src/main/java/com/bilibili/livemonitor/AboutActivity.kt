package com.bilibili.livemonitor

import android.graphics.Typeface
import android.os.Bundle
import android.widget.LinearLayout
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import com.bilibili.livemonitor.util.ChangelogParser

class AboutActivity : AppCompatActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_about)

        // 同 LogActivity：edge-to-edge 下把状态栏高度补进顶部 padding
        val root = findViewById<LinearLayout>(R.id.aboutRoot)
        val basePaddingTop = root.paddingTop
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars())
            v.setPadding(v.paddingLeft, basePaddingTop + bars.top, v.paddingRight, v.paddingBottom)
            insets
        }

        findViewById<TextView>(R.id.tvAboutVersion).text =
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})"

        renderChangelog(readChangelogText())
    }

    // internal：测试注入文本驱动渲染分支
    internal fun readChangelogText(): String {
        return runCatching {
            assets.open("CHANGELOG.txt").bufferedReader().use { it.readText() }
        }.getOrDefault("")
    }

    internal fun renderChangelog(text: String) {
        val container = findViewById<LinearLayout>(R.id.changelogContainer)
        container.removeAllViews()
        val notes = ChangelogParser.parse(text)
        if (notes.isEmpty()) {
            container.addView(TextView(this).apply {
                this.text = "暂无历史版本日志"
                textSize = 14f
            })
            return
        }
        for (note in notes) {
            container.addView(TextView(this).apply {
                this.text = if (note.date.isBlank()) note.tag else "${note.tag} (${note.date})"
                textSize = 15f
                setTypeface(typeface, Typeface.BOLD)
                setPadding(0, 24, 0, 8)
            })
            for (line in note.lines) {
                container.addView(TextView(this).apply {
                    // 去掉 commit hash 前缀，只留 subject（空间给文案）
                    this.text = line.substringAfter(' ', line)
                    textSize = 13f
                    setPadding(24, 2, 0, 2)
                })
            }
        }
    }
}
