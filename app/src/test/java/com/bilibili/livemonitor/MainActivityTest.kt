package com.bilibili.livemonitor

import android.Manifest
import android.app.Application
import android.content.Context
import android.content.Intent
import androidx.test.core.app.ApplicationProvider
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.PreferenceManager
import com.bilibili.livemonitor.util.QqShare
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
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
        // 节流自动更新检查，避免 onCreate 对 api.github.com 发起真实网络请求；
        // 测自动检测的用例自行重置该时间戳
        prefs.setLastUpdateCheckTime(System.currentTimeMillis())
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
    fun `B站App不可用时 按钮为灰色且点击启动系统选择器`() {
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

        // 应启动系统选择器（ACTION_CHOOSER），主 intent 是 https
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)
        val mainIntent = started?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals("https://live.bilibili.com/11258892", mainIntent?.dataString)
    }

    @Test
    fun `点击打开直播间 启动系统选择器且bilibili注入EXTRA_INITIAL_INTENTS`() {
        makeBilibiliInstalled("tv.danmaku.bili" to "哔哩哔哩")
        makeBrowsers("com.android.chrome" to "Chrome")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        LiveCheckService.isRunning = false

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenLive
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)

        // 主 intent 是 https（浏览器列表来源）
        val mainIntent = started?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, mainIntent?.action)
        assertEquals("https://live.bilibili.com/11258892", mainIntent?.dataString)

        // EXTRA_INITIAL_INTENTS 注入 bilibili:// intent，排在选择器最前
        val initialIntents = started?.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
        assertTrue("应有 EXTRA_INITIAL_INTENTS", initialIntents != null)
        assertEquals("应注入 1 个 initial intent", 1, initialIntents!!.size)
        val bilibiliIntent = initialIntents[0] as Intent
        assertEquals(Intent.ACTION_VIEW, bilibiliIntent.action)
        assertEquals("bilibili://live/11258892", bilibiliIntent.dataString)
        // 不带 setPackage，让系统选择器自己列所有能解析的 bilibili 客户端
        assertEquals(null, bilibiliIntent.`package`)
    }

    @Test
    fun `点击打开空间主页 启动系统选择器且bilibili注入EXTRA_INITIAL_INTENTS`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenSpace
        ).performClick()

        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)

        // 主 intent 是 https（浏览器列表来源）
        val mainIntent = started?.getParcelableExtra<Intent>(Intent.EXTRA_INTENT)
        assertEquals(Intent.ACTION_VIEW, mainIntent?.action)
        assertEquals("https://space.bilibili.com/251990176", mainIntent?.dataString)

        // EXTRA_INITIAL_INTENTS 注入 bilibili://space intent，排在选择器最前
        val initialIntents = started?.getParcelableArrayExtra(Intent.EXTRA_INITIAL_INTENTS)
        assertTrue("应有 EXTRA_INITIAL_INTENTS", initialIntents != null)
        assertEquals("应注入 1 个 initial intent", 1, initialIntents!!.size)
        val bilibiliIntent = initialIntents[0] as Intent
        assertEquals(Intent.ACTION_VIEW, bilibiliIntent.action)
        assertEquals("bilibili://space/251990176", bilibiliIntent.dataString)
        // 不带 setPackage，让系统选择器自己列所有能解析的 bilibili 客户端
        assertEquals(null, bilibiliIntent.`package`)
    }
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

        // 观播静音命令在启动选择器之前发出
        val muteIntent = shadowOf(context).peekNextStartedService()
        assertEquals("应发观播静音命令而非停止命令", LiveCheckService.ACTION_WATCH_LIVE, muteIntent?.action)
        assertTrue("监控标记必须保持 true", prefs.isServiceRunning())
        assertTrue("应置观播静音", prefs.isAlertSuppressed())

        // 随后启动系统选择器
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_CHOOSER, started?.action)

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
    fun `装QQ时点分享 走SDK真卡片路径且参数正确`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var capturedParams: android.os.Bundle? = null
        activity.qqSdkSharer = object : com.bilibili.livemonitor.util.QqSdkSharer {
            override fun isAuthorized(): Boolean = true
            override fun login(
                activity: android.app.Activity,
                onAuthorized: () -> Unit,
                onCancelled: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* not called in this test */ }
            override fun shareToQQ(
                activity: android.app.Activity,
                params: android.os.Bundle,
                onComplete: () -> Unit,
                onCancel: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) {
                capturedParams = params
            }
        }

        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare).performClick()

        // 分享流程是异步的（取封面 3s 超时），Robolectric 无真网络会走兜底封面
        val deadline = System.currentTimeMillis() + 10_000
        while (capturedParams == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        assertTrue("应调用 SDK 分享", capturedParams != null)
        val target = capturedParams!!.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_TARGET_URL)!!
        assertTrue("应含 bbid 归因: $target", target.contains("bbid=8945059"))
        assertTrue("应含直播间链接: $target", target.contains("live.bilibili.com/11258892"))
        assertEquals("白绮开播啦！", capturedParams!!.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_TITLE))
        assertEquals("牢白播了吗", capturedParams!!.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_APP_NAME))
        val cover = capturedParams!!.getString(com.tencent.connect.share.QQShare.SHARE_TO_QQ_IMAGE_URL)!!
        assertTrue("封面应为有效 https 地址: $cover", cover.startsWith("https://"))
        // SDK 路径成功时不应再发系统分享
        assertNull(shadowOf(context).peekNextStartedActivity())
    }

    @Test
    fun `未授权时点分享 弹授权引导对话框`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.qqSdkSharer = object : com.bilibili.livemonitor.util.QqSdkSharer {
            override fun isAuthorized(): Boolean = false
            override fun login(
                activity: android.app.Activity,
                onAuthorized: () -> Unit,
                onCancelled: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* not called in this test */ }
            override fun shareToQQ(
                activity: android.app.Activity,
                params: android.os.Bundle,
                onComplete: () -> Unit,
                onCancel: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* 不应直接调 shareToQQ */ }
        }

        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare).performClick()
        // shareLiveRoom 走 shareScope(Dispatchers.Main) 协程，跑 cover 取 3s 超时
        // 等到引导 dialog 出现或超时
        val deadline = System.currentTimeMillis() + 10_000
        var guideDialog: androidx.appcompat.app.AlertDialog? = null
        while (guideDialog == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            guideDialog = org.robolectric.shadows.ShadowDialog.getShownDialogs()
                .map { it as androidx.appcompat.app.AlertDialog }
                .firstOrNull { it.isShowing && it.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString() == "QQ 分享需要先授权" }
            if (guideDialog == null) Thread.sleep(100)
        }
        assertNotNull("未授权应弹引导对话框", guideDialog)
        val titleView = guideDialog!!.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
        assertEquals("QQ 分享需要先授权", titleView?.text?.toString())
    }

    @Test
    fun `引导对话框选普通分享 走系统分享面板`() {
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.qqSdkSharer = object : com.bilibili.livemonitor.util.QqSdkSharer {
            override fun isAuthorized(): Boolean = false
            override fun login(
                activity: android.app.Activity,
                onAuthorized: () -> Unit,
                onCancelled: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* not called */ }
            override fun shareToQQ(
                activity: android.app.Activity,
                params: android.os.Bundle,
                onComplete: () -> Unit,
                onCancel: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* not called */ }
        }

        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare).performClick()
        // 等引导 dialog 出现
        val deadline = System.currentTimeMillis() + 10_000
        var guideDialog: androidx.appcompat.app.AlertDialog? = null
        while (guideDialog == null && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            guideDialog = org.robolectric.shadows.ShadowDialog.getShownDialogs()
                .map { it as androidx.appcompat.app.AlertDialog }
                .firstOrNull { it.isShowing && it.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString() == "QQ 分享需要先授权" }
            if (guideDialog == null) Thread.sleep(100)
        }
        // 点 "普通分享"（negative button）
        assertNotNull("应找到引导对话框", guideDialog)
        guideDialog!!.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEGATIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()

        val started = shadowOf(context).nextStartedActivity
        assertTrue("普通分享应触发系统分享面板", started != null)
        val inner = started?.getParcelableExtra<android.content.Intent>(Intent.EXTRA_INTENT)
        val sendAction = inner?.action ?: started?.action
        assertEquals(Intent.ACTION_SEND, sendAction)
    }

    @Test
    fun `授权过期 onError code=-6 重新弹引导对话框`() {
        // 模拟：用户已授权过（点过对话框的"去 QQ 授权"），session 后续过期
        // shareToQQ 回调收到 code=-6 → UI 重新弹引导对话框
        makeQqInstalled(true)
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        var shareCalled = false
        activity.qqSdkSharer = object : com.bilibili.livemonitor.util.QqSdkSharer {
            override fun isAuthorized(): Boolean = true  // session 看起来还有效
            override fun login(
                activity: android.app.Activity,
                onAuthorized: () -> Unit,
                onCancelled: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) { /* not called */ }
            override fun shareToQQ(
                activity: android.app.Activity,
                params: android.os.Bundle,
                onComplete: () -> Unit,
                onCancel: () -> Unit,
                onError: (errorCode: Int, message: String?) -> Unit
            ) {
                shareCalled = true
                // 模拟 session 过期：shareToQQ 立即回调 -6
                onError(-6, "用户未授权")
            }
        }

        activity.findViewById<com.google.android.material.button.MaterialButton>(R.id.btnShare).performClick()
        val deadline = System.currentTimeMillis() + 10_000
        while (!shareCalled && System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
        }
        assertTrue("shareToQQ 应被调用", shareCalled)
        shadowOf(android.os.Looper.getMainLooper()).idle()

        // 验证：再次弹授权引导对话框
        val dialog = org.robolectric.shadows.ShadowDialog.getShownDialogs()
            .map { it as androidx.appcompat.app.AlertDialog }
            .firstOrNull { it.isShowing && it.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)?.text?.toString() == "QQ 分享需要先授权" }
        assertNotNull("session 过期 -6 应重新弹引导", dialog)
        val titleView = dialog!!.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
        assertEquals("QQ 分享需要先授权", titleView?.text?.toString())
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

        // 抽屉里展开「后台保活设置」section 后点「去电池优化设置」按钮
        activity.showSettingsDrawer()
        expandSectionAt(activity, 0)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val sheetView = (org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as com.google.android.material.bottomsheet.BottomSheetDialog)
            .findViewById<android.view.View>(R.id.itemsContainer)!!
        sheetView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenBatterySettings
        )?.performClick()

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

        activity.showSettingsDrawer()
        expandSectionAt(activity, 0)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val sheetView = (org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as com.google.android.material.bottomsheet.BottomSheetDialog)
            .findViewById<android.view.View>(R.id.itemsContainer)!!
        sheetView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenBackgroundSettings
        )?.performClick()

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

        activity.showSettingsDrawer()
        expandSectionAt(activity, 0)
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val sheetView = (org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as com.google.android.material.bottomsheet.BottomSheetDialog)
            .findViewById<android.view.View>(R.id.itemsContainer)!!
        sheetView.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnOpenBackgroundSettings
        )?.performClick()

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

    // ---------- 应用内更新 ----------

    private class FakeUpdateChecker(
        private val responses: Map<String, String?>
    ) : com.bilibili.livemonitor.api.UpdateChecker() {
        override suspend fun httpGet(url: String): String? = responses[url]
    }

    // 远端版本号必须动态高于本地（versionCode=提交数，随提交增长，硬编码会失效）
    private fun fakeUpdateCheckerWithUpdate(
        versionCode: Int = BuildConfig.VERSION_CODE + 1
    ): FakeUpdateChecker {
        val releaseJson = """{
            "tag_name": "v1.1.2",
            "body": "更新日志内容",
            "assets": [
                {"name": "vivhite-tracker-1.1.$versionCode.apk", "browser_download_url": "https://example.com/vivhite-tracker-1.1.$versionCode.apk"},
                {"name": "version.json", "browser_download_url": "https://example.com/version.json"}
            ]
        }"""
        return FakeUpdateChecker(
            mapOf(
                com.bilibili.livemonitor.api.UpdateChecker.LATEST_RELEASE_API to releaseJson,
                "https://example.com/version.json" to
                    """{"versionCode":$versionCode,"versionName":"1.1.$versionCode"}"""
            )
        )
    }

    // onCreate 可能已弹权限引导对话框，必须等待「新实例」而不是拿最新的旧对话框
    private fun waitForNewDialog(
        baseline: android.app.Dialog?,
        timeoutMs: Long = 10_000
    ): androidx.appcompat.app.AlertDialog? {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            if (dialog != null && dialog !== baseline) {
                return dialog as androidx.appcompat.app.AlertDialog
            }
            Thread.sleep(100)
        }
        return null
    }

    @Test
    fun `检查更新按钮与设置入口存在`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        val btn = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnCheckUpdate
        )
        assertEquals("检查更新", btn.text.toString())
        val settings = activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnSettings
        )
        assertEquals("设置", settings.text.toString())
    }

    @Test
    fun `手动检查更新 有新版本时弹更新对话框`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.updateChecker = fakeUpdateCheckerWithUpdate()

        val baseline = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnCheckUpdate
        ).performClick()

        val dialog = waitForNewDialog(baseline)
        assertTrue("应弹出更新对话框", dialog != null)
        val expectedVersion = BuildConfig.VERSION_CODE + 1
        val title = dialog!!.findViewById<android.widget.TextView>(androidx.appcompat.R.id.alertTitle)
        assertTrue(
            "标题应含新版本号: ${title?.text}",
            title?.text?.contains("发现新版本 v1.1.$expectedVersion") == true
        )
        val message = dialog.findViewById<android.widget.TextView>(android.R.id.message)
        assertTrue("应含更新日志: ${message?.text}", message?.text?.contains("更新日志内容") == true)
    }

    @Test
    fun `手动检查更新 已最新时 Toast 提示`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        // 远端版本号远低于本地 → UpToDate
        activity.updateChecker = fakeUpdateCheckerWithUpdate(versionCode = 1)

        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnCheckUpdate
        ).performClick()

        val deadline = System.currentTimeMillis() + 10_000
        var toast: String? = null
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            toast = org.robolectric.shadows.ShadowToast.getTextOfLatestToast()
            if (toast == "已是最新版本") break
            Thread.sleep(100)
        }
        assertEquals("已是最新版本", toast)
    }

    @Test
    fun `手动检查更新 网络失败时弹错误对话框可跳发布页`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.updateChecker = FakeUpdateChecker(emptyMap()) // 所有请求返回 null → network error

        val baseline = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        activity.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnCheckUpdate
        ).performClick()

        val dialog = waitForNewDialog(baseline)
        assertTrue("应弹出错误对话框", dialog != null)
        val message = dialog!!.findViewById<android.widget.TextView>(android.R.id.message)
        assertTrue(
            "应提示网络错误: ${message?.text}",
            message?.text?.contains("无法连接 GitHub") == true
        )

        dialog.getButton(androidx.appcompat.app.AlertDialog.BUTTON_POSITIVE).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        val started = shadowOf(context).nextStartedActivity
        assertEquals(Intent.ACTION_VIEW, started?.action)
        assertTrue(
            "应跳最新发布页: ${started?.dataString}",
            started?.dataString?.contains("releases/latest") == true
        )
    }

    @Test
    fun `更新设置对话框 开关状态与prefs双向同步`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // 更新设置入口整合到抽屉，直接调原对话框方法
        activity.showUpdateSettingsDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as androidx.appcompat.app.AlertDialog
        assertTrue(dialog.isShowing)
        val switchAutoCheck = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.switchAutoCheck
        )!!
        val switchAutoDownload = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(
            R.id.switchAutoDownload
        )!!
        // 默认：自动检查开、自动下载关
        assertTrue(switchAutoCheck.isChecked)
        assertFalse(switchAutoDownload.isChecked)

        switchAutoCheck.isChecked = false
        switchAutoDownload.isChecked = true
        assertFalse(prefs.isAutoCheckUpdate())
        assertTrue(prefs.isAutoDownloadUpdate())
    }

    @Test
    fun `自动检测到新版本 弹对话框且可忽略此版本`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        val fakeVersion = BuildConfig.VERSION_CODE + 1
        activity.updateChecker = fakeUpdateCheckerWithUpdate(versionCode = fakeVersion)
        prefs.setLastUpdateCheckTime(0L)

        activity.autoCheckUpdateIfDue()

        val dialog = waitForNewDialog(
            org.robolectric.shadows.ShadowDialog.getLatestDialog()
        )
        assertTrue("自动检测应弹更新对话框", dialog != null)
        // 点「忽略此版本」后持久化 versionCode，且对话框消失
        dialog!!.getButton(androidx.appcompat.app.AlertDialog.BUTTON_NEUTRAL).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertEquals(fakeVersion, prefs.getDismissedVersionCode())
        assertFalse(dialog.isShowing)

        // 再次自动检测：被忽略的版本不再弹
        val before = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        activity.checkForUpdate(manual = false)
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            shadowOf(android.os.Looper.getMainLooper()).idle()
            Thread.sleep(100)
            // 等协程跑完一轮即可（无新对话框创建）
            if (org.robolectric.shadows.ShadowDialog.getLatestDialog() !== before) break
        }
        assertTrue(
            "被忽略的版本不应再弹",
            org.robolectric.shadows.ShadowDialog.getLatestDialog() === before
        )
    }

    @Test
    fun `自动检测未到时 不发起检查`() {
        var apiCalled = false
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.updateChecker = object : com.bilibili.livemonitor.api.UpdateChecker() {
            override suspend fun httpGet(url: String): String? {
                apiCalled = true
                return null
            }
        }
        // setUp 已写入当前时间，24h 内不应再检查
        activity.autoCheckUpdateIfDue()
        shadowOf(android.os.Looper.getMainLooper()).idle()
        assertFalse(apiCalled)
    }

    // ---------- 提醒铃声自定义 ----------

    @Test
    fun `点提醒铃声按钮 弹出铃声设置对话框`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // 设置入口整合到抽屉，弹出原铃声对话框的内部方法
        activity.showAlertDialogSoundDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("应弹出 AlertDialog", dialog is androidx.appcompat.app.AlertDialog)
    }

    @Test
    fun `铃声对话框显示 4 个内置铃声选项`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAlertDialogSoundDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.builtinSoundsContainer)!!
        assertEquals(4, container.childCount)
    }

    @Test
    fun `点恢复默认 清空 prefs 并关闭对话框`() {
        prefs.setAlertSoundUri("builtin:alert_gentle")
        prefs.setAlertSoundTitle("柔和提示")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAlertDialogSoundDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        dialog.findViewById<com.google.android.material.button.MaterialButton>(
            R.id.btnRestoreDefault
        )!!.performClick()

        assertEquals("", prefs.getAlertSoundUri())
        assertEquals("", prefs.getAlertSoundTitle())
    }

    @Test
    fun `未设置铃声时 内置默认项被勾选`() {
        prefs.setAlertSoundUri("")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAlertDialogSoundDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.builtinSoundsContainer)!!
        // 第 1 个是 CLASSIC_1（DEFAULT），应被勾选
        val firstRb = container.getChildAt(0).findViewById<android.widget.RadioButton>(R.id.rbSound)!!
        assertTrue("默认应勾选经典提醒 1", firstRb.isChecked)
    }

    @Test
    fun `已选内置铃声时 对应项被勾选`() {
        prefs.setAlertSoundUri("builtin:alert_gentle")
        prefs.setAlertSoundTitle("柔和提示")
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showAlertDialogSoundDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val container = dialog.findViewById<android.widget.LinearLayout>(R.id.builtinSoundsContainer)!!
        // alert_gentle 是第 3 个（CLASSIC_1, CLASSIC_2, GENTLE, URGENT）
        val gentleRb = container.getChildAt(2).findViewById<android.widget.RadioButton>(R.id.rbSound)!!
        assertTrue("应勾选柔和提示", gentleRb.isChecked)
    }

    // ---------- 活动监控设置 ----------

    @Test
    fun `点活动监控按钮 弹出设置对话框`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()

        // 设置入口整合到抽屉，弹出原活动对话框的内部方法
        activity.showActivitySettingsDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
        assertTrue("应弹出 AlertDialog", dialog is androidx.appcompat.app.AlertDialog)
    }

    @Test
    fun `活动监控对话框有 4 个开关`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showActivitySettingsDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        assertNotNull(dialog.findViewById(R.id.switchMonitorVideos))
        assertNotNull(dialog.findViewById(R.id.switchMonitorPinned))
        assertNotNull(dialog.findViewById(R.id.switchMonitorDynamics))
        assertNotNull(dialog.findViewById(R.id.switchAlertRingOnActivity))
    }

    @Test
    fun `活动监控开关切换落 prefs`() {
        val activity = Robolectric.buildActivity(MainActivity::class.java).setup().get()
        activity.showActivitySettingsDialog()

        val dialog = org.robolectric.shadows.ShadowDialog.getLatestDialog() as androidx.appcompat.app.AlertDialog
        val switchVideos = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorVideos)!!
        val switchDynamics = dialog.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchMonitorDynamics)!!

        assertEquals("默认应开", true, switchVideos.isChecked)
        switchVideos.isChecked = true
        switchDynamics.isChecked = true

        assertEquals(true, prefs.isMonitorVideos())
        assertEquals(true, prefs.isMonitorDynamics())
    }

    private fun expandSectionAt(activity: MainActivity, position: Int) {
        // 点第 N 个条目的 itemRoot 让其内嵌容器展开
        val sheetDialog = org.robolectric.shadows.ShadowDialog.getLatestDialog()
            as com.google.android.material.bottomsheet.BottomSheetDialog
        val itemsContainer = sheetDialog.findViewById<android.widget.LinearLayout>(R.id.itemsContainer)!!
        val itemView = itemsContainer.getChildAt(position)
        itemView.findViewById<android.view.View>(R.id.itemRoot).performClick()
        shadowOf(android.os.Looper.getMainLooper()).idle()
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
}
