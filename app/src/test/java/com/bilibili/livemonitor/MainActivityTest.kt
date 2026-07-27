package com.bilibili.livemonitor

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * MainActivity 用户场景（P4）。
 * 真机场景：重装/冷启动后打开 App 自动恢复监控、按钮启停、状态一眼可见。
 */
@RunWith(RobolectricTestRunner::class)
class MainActivityTest {

    private val context: Application = ApplicationProvider.getApplicationContext()
    private lateinit var prefs: PreferenceManager

    @Before
    fun setUp() {
        prefs = PreferenceManager(context)
        LiveCheckService.isRunning = false
        LiveCheckService.isUserStopped = false
        // 授权通知权限，否则点开始监控会走权限申请分支而不启动服务
        shadowOf(context).grantPermissions(Manifest.permission.POST_NOTIFICATIONS)
    }

    @After
    fun tearDown() {
        LiveCheckService.isRunning = false
        LiveCheckService.isUserStopped = false
        org.robolectric.util.ReflectionHelpers.setStaticField(
            android.os.Build::class.java, "MANUFACTURER", originalManufacturer
        )
    }

    @Test
    fun `冷启动且监控标记为true 自动恢复监控`() {
        // 真机场景：服务被系统杀掉后用户重新打开 App，无需手动点开始就恢复监控
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = false

        Robolectric.buildActivity(MainActivity::class.java).create()

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
    }

    @Test
    fun `冷启动且监控标记为false 不启动服务`() {
        prefs.setServiceRunning(false)

        Robolectric.buildActivity(MainActivity::class.java).create()

        assertNull(shadowOf(context).peekNextStartedService())
    }

    @Test
    fun `点开始监控 启动服务且按钮变为停止监控`() {
        prefs.setServiceRunning(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnToggle
        ).performClick()

        val started = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService::class.java.name, started?.component?.className)
        assertTrue(prefs.isServiceRunning())
        assertEquals(
            "停止监控",
            activity.findViewById<com.google.android.material.button.MaterialButton>(
                R.id.btnToggle
            ).text.toString()
        )
    }

    @Test
    fun `点停止监控 发送停止命令`() {
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 消费掉冷启动自动恢复发出的启动 intent
        shadowOf(context).peekNextStartedService()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnToggle
        ).performClick()

        val stopIntent = shadowOf(context).peekNextStartedService()
        assertEquals(LiveCheckService.ACTION_STOP_SERVICE, stopIntent?.action)
        assertFalse(prefs.isServiceRunning())
    }

    @Test
    fun `有检测记录时 显示上次检测时间和状态`() {
        prefs.setLastCheck(System.currentTimeMillis(), isLive = true, success = true)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvLastCheck).text.toString()
        assertTrue(text.contains("上次检测"))
        assertTrue(text.contains("直播中"))
    }

    @Test
    fun `检测失败时 状态显示检测失败`() {
        // 真机场景：Doze 网络错误落盘 success=false，用户打开 App 要能看到异常
        prefs.setLastCheck(System.currentTimeMillis(), isLive = false, success = false)

        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvLastCheck).text.toString()
        assertTrue(text.contains("检测失败"))
    }

    @Test
    fun `点击查看运行日志 打开LogActivity`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnViewLog
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(LogActivity::class.java.name, started?.component?.className)
    }

    // ---------- 新增：版本信息 / GitHub / 打开直播间 / 功能说明 ----------

    @Test
    fun `版本行显示版本号和8位git哈希`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvVersion).text.toString()
        assertTrue("应含版本号: $text", text.startsWith("v${BuildConfig.VERSION_NAME}"))
        assertTrue(
            "应含 8 位哈希: $text",
            Regex("\\([0-9a-f]{8}\\)").containsMatchIn(text)
        )
    }

    @Test
    fun `点GitHub按钮 打开项目地址`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenGithub
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https://github.com/XenoAmess/vivhite-tracker", started?.dataString)
    }

    @Test
    fun `B站App可用时 打开直播间按钮为绿色`() {
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val expected = androidx.core.content.ContextCompat.getColor(activity, R.color.green_500)
        val actual = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).backgroundTintList?.defaultColor
        assertEquals(expected, actual)
    }

    @Test
    fun `B站App不可用时 按钮为灰色且点击跳转浏览器`() {
        makeBilibiliInstalled()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val expected = androidx.core.content.ContextCompat.getColor(activity, android.R.color.darker_gray)
        val actual = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).backgroundTintList?.defaultColor
        assertEquals(expected, actual)

        LiveCheckService.isRunning = false
        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("https://live.bilibili.com/11258892", started?.dataString)
    }

    @Test
    fun `B站App可用时 弹选择器点客户端项直接打开B站App`() {
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        makeBrowsers()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        LiveCheckService.isRunning = false

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        // 单客户端 + 通用浏览器 = 两个选项，弹选择器，点第 0 项（客户端）
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        clickDialogItem(dialog, 0)

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertEquals("bilibili://live/11258892", started?.dataString)
        assertEquals("应强制投递给B站客户端", "tv.danmaku.bili", started?.`package`)
    }

    // ---------- 多客户端/多浏览器选择器 ----------

    @Test
    fun `装两个客户端 弹选择器且浏览器在最后`() {
        makeBilibiliInstalled(
            "tv.danmaku.bili" to "哔哩哔哩",
            "com.bilibili.app.blue" to "哔哩哔哩概念"
        )
        // 无已探测浏览器时补通用浏览器项
        makeBrowsers()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val labels = (0 until dialog.listView.adapter.count).map {
            dialog.listView.adapter.getItem(it).toString()
        }
        assertEquals(listOf("哔哩哔哩", "哔哩哔哩概念", "浏览器"), labels)

        // 点概念版：bilibili:// + setPackage(blue)
        clickDialogItem(dialog, 1)
        val started = shadowOf(context).nextStartedActivity
        assertEquals("bilibili://live/11258892", started?.dataString)
        assertEquals("com.bilibili.app.blue", started?.`package`)
    }

    @Test
    fun `一个客户端多个浏览器 全部列出且浏览器可点选`() {
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        makeBrowsers(
            "com.android.chrome" to "Chrome",
            "com.quark.browser" to "夸克"
        )
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        val labels = (0 until dialog.listView.adapter.count).map {
            dialog.listView.adapter.getItem(it).toString()
        }
        assertEquals("bilibili 客户端必须在浏览器之前", listOf("哔哩哔哩", "Chrome", "夸克"), labels)

        // 点夸克：https + setPackage(夸克)
        clickDialogItem(dialog, 2)
        val started = shadowOf(context).nextStartedActivity
        assertEquals("https://live.bilibili.com/11258892", started?.dataString)
        assertEquals("com.quark.browser", started?.`package`)
    }

    @Test
    fun `bilibili也注册了https时 不出现在浏览器段`() {
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        makeBrowsers(
            "tv.danmaku.bili" to "哔哩哔哩", // bilibili 自身也能开 https
            "com.android.chrome" to "Chrome"
        )
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        val labels = (0 until dialog.listView.adapter.count).map {
            dialog.listView.adapter.getItem(it).toString()
        }
        assertEquals("bilibili 不得在浏览器段重复出现", listOf("哔哩哔哩", "Chrome"), labels)
    }

    @Test
    fun `监控中点打开直播间 置观播静音但不停止监控`() {        // 用户需求：点打开直播间后持续监控，本场直播结束前不再响铃
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        prefs.setServiceRunning(true)
        LiveCheckService.isRunning = true
        LiveCheckService.lastLiveStatus = true
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 消费冷启动自动恢复的启动 intent
        shadowOf(context).peekNextStartedService()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        // 弹选择器后点客户端项才触发静音+跳转
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        clickDialogItem(dialog, 0)

        val muteIntent = shadowOf(context).peekNextStartedService()
        assertEquals("应发观播静音命令而非停止命令", LiveCheckService.ACTION_WATCH_LIVE, muteIntent?.action)
        assertTrue("监控标记必须保持 true", prefs.isServiceRunning())
        assertTrue("应置观播静音", prefs.isAlertSuppressed())
        val jumpIntent = shadowOf(context).nextStartedActivity
        assertEquals("bilibili://live/11258892", jumpIntent?.dataString)

        // tvStatus 应显示本场静音
        val statusText = activity.findViewById<android.widget.TextView>(R.id.tvStatus).text.toString()
        assertTrue("应显示本场静音: $statusText", statusText.contains("本场静音"))
    }

    @Test
    fun `底部显示功能说明文案`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvDescription).text.toString()
        // 断言稳定子串，不绑定易变措辞（文案曾压缩防跨行）
        assertTrue(text.contains("每分钟检查"))
        assertTrue(text.contains("响铃"))
        assertTrue(text.contains("电池优化"))
        assertTrue(text.contains("通知栏"))
    }

    @Test
    fun `首次启动底部展示邓煜名言`() {
        // 新装首启（firstLaunchDone 默认 false）：必须展示邓煜那条
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvQuote).text.toString()
        assertTrue("首启应展示邓煜名言: $text", text.contains("有哪些优秀的百合同人作品"))
        assertTrue(text.contains("邓煜"))
        assertTrue(text.contains("——"))
        // 且首启标记应落盘
        assertTrue(prefs.isFirstLaunchDone())
    }

    @Test
    fun `非首启底部名言含作者分隔符`() {
        prefs.setFirstLaunchDone(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val text = activity.findViewById<android.widget.TextView>(R.id.tvQuote).text.toString()
        assertTrue("名言非空: $text", text.length > 6)
        assertTrue("应含作者分隔符: $text", text.contains("——"))
        assertTrue(text.startsWith("「"))
    }

    // ---------- 后台运行设置：统一入口按厂商路由 ----------

    private val originalManufacturer: String = android.os.Build.MANUFACTURER

    private fun setManufacturer(value: String) {
        org.robolectric.util.ReflectionHelpers.setStaticField(
            android.os.Build::class.java, "MANUFACTURER", value
        )
    }

    private fun makeBatteryIntentResolvable() {
        // openBatterySettings 里 resolveActivity 判空需要对应的 resolve info
        val intent = Intent(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            android.net.Uri.parse("package:com.bilibili.livemonitor")
        )
        shadowOf(context.packageManager).addResolveInfoForIntent(
            intent,
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = "com.android.settings"
                    name = "com.android.settings.Settings"
                }
            }
        )
    }

    @Test
    fun `国产ROM厂商引导只弹一次 之后启动不再重复弹`() {
        // 用户反馈：明明设置过了启动还弹。厂商设置状态无 API 可读，
        // 只能持久化"已引导"标志，主界面按钮作为再入口
        setManufacturer("Xiaomi")

        // 首次启动：应弹厂商引导
        Robolectric.buildActivity(MainActivity::class.java).setup()
        val firstDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("首启应弹厂商引导", collectDialogTexts(firstDialog).any { it.contains("后台保活设置") })

        // 二次启动（新 Activity 实例，模拟冷启动）：不应再弹
        Robolectric.buildActivity(MainActivity::class.java).setup()
        val secondDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue(
            "已引导过不应重复弹: ${collectDialogTexts(secondDialog)}",
            collectDialogTexts(secondDialog).none { it.contains("后台保活设置") }
        )
    }

    private fun collectDialogTexts(dialog: android.app.Dialog?): List<String> {
        if (dialog == null) return emptyList()
        val texts = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is android.widget.TextView) texts.add(view.text.toString())
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        dialog.window?.decorView?.let { collect(it) }
        return texts
    }

    // ---------- QQ 群 ----------

    private fun makeQqInstalled(installed: Boolean) {
        val pm = shadowOf(context.packageManager)
        val variants = listOf(
            "com.tencent.mobileqq", "com.tencent.tim",
            "com.tencent.mobileqqi", "com.tencent.qqlite"
        )
        for (pkg in variants) pm.removePackage(pkg)
        if (installed) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = "com.tencent.mobileqq"
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    this.packageName = "com.tencent.mobileqq"
                    nonLocalizedLabel = "QQ"
                }
            })
        }
    }

    @Test
    fun `装QQ时点分享 发出系统分享面板且文本含bbid归因`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.ImageButton>(R.id.btnShare).performClick()

        // 分享流程是异步的（取封面 3s 超时），Robolectric 无真网络会走兜底封面
        val deadline = System.currentTimeMillis() + 10_000
        var started: android.content.Intent? = null
        while (started == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            started = shadowOf(context).nextStartedActivity
            if (started == null) Thread.sleep(100)
        }
        assertTrue("应发出分享 intent", started != null)
        // 主路径为 ACTION_SEND（可能被 createChooser 包装为 ACTION_CHOOSER）
        val inner = started?.getParcelableExtra<android.content.Intent>(Intent.EXTRA_INTENT)
        val sendAction = inner?.action ?: started?.action
        assertEquals(Intent.ACTION_SEND, sendAction)
        val text = (inner ?: started)?.getStringExtra(Intent.EXTRA_TEXT) ?: ""
        assertTrue("应含 bbid 归因: $text", text.contains("bbid=8945059"))
        assertTrue("应含直播间链接: $text", text.contains("live.bilibili.com/11258892"))
    }

    @Test
    fun `未装QQ时点分享 同样走系统分享面板`() {
        makeQqInstalled(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.ImageButton>(R.id.btnShare).performClick()

        val deadline = System.currentTimeMillis() + 10_000
        var started: android.content.Intent? = null
        while (started == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            started = shadowOf(context).nextStartedActivity
            if (started == null) Thread.sleep(100)
        }
        assertTrue(started != null)
        val inner = started?.getParcelableExtra<android.content.Intent>(Intent.EXTRA_INTENT)
        val sendAction = inner?.action ?: started?.action
        assertEquals(Intent.ACTION_SEND, sendAction)
    }

    @Test
    fun `分享兜底复制链接 剪贴板含bbid归因链接`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.copyShareLinkToClipboard()

        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        val text = cm.primaryClip?.getItemAt(0)?.text?.toString() ?: ""
        assertTrue("应含直播间链接: $text", text.contains("live.bilibili.com/11258892"))
        assertTrue("应含 bbid 归因: $text", text.contains("bbid=8945059"))
        assertTrue("应含 share_source: $text", text.contains("share_source=copy_link"))
    }

    @Test
    fun `三个QQ群项渲染且头像互不相同`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val row = activity.findViewById<android.widget.LinearLayout>(R.id.qqGroupRow)
        assertEquals(3, row.childCount)
        val names = (0 until row.childCount).map {
            row.getChildAt(it).findViewById<android.widget.TextView>(R.id.tvQqName).text.toString()
        }
        assertEquals(listOf("数学研讨", "游戏联机", "慕白者琉"), names)
        val avatars = com.bilibili.livemonitor.util.QqGroups.groups.map { it.avatarRes }.toSet()
        assertEquals(3, avatars.size)
    }

    @Test
    fun `装QQ时点群 拉起mqqapi群卡片且强制投递`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.LinearLayout>(R.id.qqGroupRow)
            .getChildAt(0).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        val url = started?.dataString ?: ""
        assertTrue("应为 mqqapi scheme: $url", url.startsWith("mqqapi://card/show_pslcard"))
        assertTrue("应含数学研讨群号: $url", url.contains("uin=774800912"))
        assertTrue("应含 card_type=group: $url", url.contains("card_type=group"))
        assertEquals("com.tencent.mobileqq", started?.`package`)
    }

    @Test
    fun `装QQ时点第三个群 群号为慕白者琉`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.LinearLayout>(R.id.qqGroupRow)
            .getChildAt(2).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertTrue(started?.dataString?.contains("uin=292901300") == true)
    }

    @Test
    fun `未装QQ时点群 弹群号且可复制`() {
        makeQqInstalled(false)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<android.widget.LinearLayout>(R.id.qqGroupRow)
            .getChildAt(1).performClick()

        assertNull(shadowOf(context).peekNextStartedActivity())
        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val texts = mutableListOf<String>()
        fun collect(view: android.view.View) {
            if (view is android.widget.TextView) texts.add(view.text.toString())
            if (view is android.view.ViewGroup) {
                for (i in 0 until view.childCount) collect(view.getChildAt(i))
            }
        }
        dialog.window?.decorView?.let { collect(it) }
        assertTrue("应含游戏联机群号: $texts", texts.any { it.contains("775455331") })

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val cm = context.getSystemService(Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        assertEquals("775455331", cm.primaryClip?.getItemAt(0)?.text?.toString())
    }

    @Test
    fun `原生机点后台运行设置 直接打开电池优化设置页`() {        setManufacturer("Google")
        makeBatteryIntentResolvable()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnBackgroundSettings
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            started?.action
        )
    }

    @Test
    fun `荣耀点后台运行设置 弹厂商对话框且不提供电池优化死路选项`() {
        // 荣耀真机实测：标准电池优化 intent 被系统空转，点了毫无反应，
        // 所以对话框里绝不能出现"电池优化设置"选项
        setManufacturer("HONOR")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnBackgroundSettings
        ).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        assertEquals(
            "去厂商设置",
            dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).text.toString()
        )
        // AppCompat 未设置 neutral 时按钮仍在布局中但为 GONE
        val neutral = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
        assertTrue(
            "荣耀上不应提供电池优化死路选项",
            neutral.visibility != android.view.View.VISIBLE
        )

        // 点"去厂商设置"应深链到荣耀启动管理（显式 component）。
        // AlertDialog 按钮点击经 Handler Message 分发，需 idle 主线程才执行
        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val started = shadowOf(context).nextStartedActivity
        assertEquals("com.hihonor.systemmanager", started?.component?.packageName)
    }

    @Test
    fun `小米点后台运行设置 对话框保留电池优化补充入口`() {
        // 小米的自启动是主路径，但标准电池优化 intent 在 MIUI 上有效，作为补充保留
        setManufacturer("Xiaomi")
        makeBatteryIntentResolvable()
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnBackgroundSettings
        ).performClick()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        val neutral = dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL)
        assertEquals("电池优化设置", neutral.text.toString())

        neutral.performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val started = shadowOf(context).nextStartedActivity
        assertEquals(
            android.provider.Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            started?.action
        )
    }

    private fun makeBilibiliInstalled(vararg variants: Pair<String, String>) {
        // 多变体注入：label 供选择器展示（getApplicationLabel 读取）
        // ShadowPackageManager 部分状态为 static，先清空变体表防泄漏
        val pm = shadowOf(context.packageManager)
        val all = listOf(
            "tv.danmaku.bili", "com.bilibili.app.blue",
            "tv.danmaku.bilibilihd", "com.bilibili.app.in"
        )
        for (pkg in all) pm.removePackage(pkg)
        for ((pkg, label) in variants) {
            pm.installPackage(android.content.pm.PackageInfo().apply {
                packageName = pkg
                applicationInfo = android.content.pm.ApplicationInfo().apply {
                    this.packageName = pkg
                    nonLocalizedLabel = label
                }
            })
        }
    }

    private fun makeBrowsers(vararg browsers: Pair<String, String>) {
        // 注入 https VIEW 的 resolveInfo，模拟多个已装浏览器。
        // setResolveInfosForIntent 整体替换，保证列表确定（避开 static Map 泄漏）
        val intent = Intent(
            Intent.ACTION_VIEW,
            android.net.Uri.parse("https://live.bilibili.com/11258892")
        )
        val infos = browsers.map { (pkg, label) ->
            android.content.pm.ResolveInfo().apply {
                activityInfo = android.content.pm.ActivityInfo().apply {
                    packageName = pkg
                    name = "$pkg.MainActivity"
                    nonLocalizedLabel = label
                }
            }
        }
        shadowOf(context.packageManager).setResolveInfosForIntent(intent, infos)
    }

    private fun clickDialogItem(dialog: androidx.appcompat.app.AlertDialog, position: Int) {
        val lv = dialog.listView
        val adapter = lv.adapter
        val view = adapter.getView(position, null, lv)
        lv.performItemClick(view, position, adapter.getItemId(position))
        shadowOf(android.os.Looper.getMainLooper()).idle()
    }
}
