package com.bilibili.livemonitor

import android.widget.LinearLayout
import android.widget.TextView
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner

@RunWith(RobolectricTestRunner::class)
class AboutActivityTest {

    @Test
    fun `关于页显示版本号与hash`() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val tv = activity.findViewById<TextView>(R.id.tvAboutVersion)
        assertEquals(
            "v${BuildConfig.VERSION_NAME} (${BuildConfig.GIT_HASH})",
            tv.text.toString()
        )
        val toolbar = activity.findViewById<com.google.android.material.appbar.MaterialToolbar>(R.id.aboutToolbar)
        assertEquals("关于", toolbar.title.toString())
        assertTrue(toolbar.navigationIcon != null)
    }

    @Test
    fun `关于页渲染真实打包的更新日志`() {
        // CHANGELOG.txt 由 generateChangelog task 打进 assets，Robolectric 可读
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        val container = activity.findViewById<LinearLayout>(R.id.changelogContainer)
        assertTrue("至少渲染一个版本段", container.childCount >= 2)
        val first = (container.getChildAt(0) as TextView).text.toString()
        assertTrue("首段应是版本号段头: $first", first.startsWith("v"))
    }

    @Test
    fun `空changelog显示兜底文案`() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        activity.renderChangelog("")
        val container = activity.findViewById<LinearLayout>(R.id.changelogContainer)
        assertEquals(1, container.childCount)
        assertEquals("暂无历史版本日志", (container.getChildAt(0) as TextView).text.toString())
    }

    @Test
    fun `渲染时去掉commit hash前缀只留subject`() {
        val activity = Robolectric.buildActivity(AboutActivity::class.java).setup().get()
        activity.renderChangelog("## v1.6.0 (2026-08-02)\nd0f09c9 fix(service): 观播静音卡死修复\n")
        val container = activity.findViewById<LinearLayout>(R.id.changelogContainer)
        assertEquals(2, container.childCount)
        assertEquals("v1.6.0 (2026-08-02)", (container.getChildAt(0) as TextView).text.toString())
        assertEquals(
            "fix(service): 观播静音卡死修复",
            (container.getChildAt(1) as TextView).text.toString()
        )
    }
}
