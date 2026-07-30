package com.bilibili.livemonitor.util

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.NetworkInfo
import android.provider.Settings
import androidx.test.core.app.ApplicationProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowNetwork
import org.robolectric.shadows.ShadowNetworkCapabilities

/**
 * AppUpdater（应用内更新基础设施）。
 * 用户场景：点「检查更新」→ 新版 → WiFi 下自动下载 → 弹系统安装器；
 * 无安装权限时引导去「未知来源」设置页。
 */
@RunWith(RobolectricTestRunner::class)
class AppUpdaterTest {

    private val context: Application = ApplicationProvider.getApplicationContext()

    @org.junit.Before
    fun setUp() {
        // FileProvider.sCache 跨 sandbox 残留（详见 FileProviderTestUtil）
        FileProviderTestUtil.clearFileProviderCache()
    }

    @Test
    fun `apkFile 路径在updates子目录且文件名带版本号`() {
        val f = AppUpdater.apkFile(context, "1.5.0")
        assertEquals("vivhite-tracker-1.5.0.apk", f.name)
        assertEquals("updates", f.parentFile?.name)
        assertTrue(f.absolutePath.startsWith(context.filesDir.absolutePath))
    }

    @Test
    fun `buildInstallIntent 携带安装器三要素`() {
        // 用户点"安装"后系统安装器必须能读到这个 APK：
        // content:// URI（FileProvider 授权）+ APK mime + 读权限 flag，缺一不可
        val apk = AppUpdater.apkFile(context, "1.5.0")
        apk.parentFile?.mkdirs()
        apk.writeBytes(byteArrayOf(1, 2, 3))

        val intent = AppUpdater.buildInstallIntent(context, apk)

        assertEquals(Intent.ACTION_VIEW, intent.action)
        assertEquals("application/vnd.android.package-archive", intent.type)
        assertEquals("content", intent.data?.scheme)
        assertTrue(
            "FileProvider URI 必须带本应用 authority",
            intent.dataString?.contains(context.packageName) == true
        )
        assertTrue(intent.flags and Intent.FLAG_GRANT_READ_URI_PERMISSION != 0)
        assertTrue(intent.flags and Intent.FLAG_ACTIVITY_NEW_TASK != 0)
    }

    @Test
    fun `canRequestInstalls 跟随系统授权状态`() {
        val pm = context.packageManager
        shadowOf(pm).setCanRequestPackageInstalls(true)
        assertTrue(AppUpdater.canRequestInstalls(context))
        shadowOf(pm).setCanRequestPackageInstalls(false)
        assertFalse(AppUpdater.canRequestInstalls(context))
    }

    @Test
    fun `unknownSourcesIntent 跳本应用的未知来源设置页`() {
        val intent = AppUpdater.unknownSourcesIntent(context)
        assertEquals(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, intent.action)
        assertEquals("package:${context.packageName}", intent.dataString)
    }

    @Test
    fun `isOnWifi 无网络时false`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        shadowOf(cm).clearAllNetworks()
        assertFalse(AppUpdater.isOnWifi(context))
    }

    @Test
    fun `isOnWifi WiFi网络true`() {
        // 自动下载仅在 WiFi 下进行（省用户流量），判断错了会在蜂窝下偷跑几百MB
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(101)
        val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_WIFI, 0, true, true
        )
        shadow.addNetwork(network, info)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
        shadow.setNetworkCapabilities(network, caps)
        shadow.setActiveNetworkInfo(info)
        shadow.setDefaultNetworkActive(true)

        assertTrue(AppUpdater.isOnWifi(context))
    }

    @Test
    fun `isOnWifi 蜂窝网络false`() {
        val cm = context.getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager
        val shadow = shadowOf(cm)
        shadow.clearAllNetworks()
        val network = ShadowNetwork.newInstance(102)
        val info = org.robolectric.shadows.ShadowNetworkInfo.newInstance(
            NetworkInfo.DetailedState.CONNECTED,
            ConnectivityManager.TYPE_MOBILE, 0, true, true
        )
        shadow.addNetwork(network, info)
        val caps = ShadowNetworkCapabilities.newInstance()
        shadowOf(caps).addTransportType(NetworkCapabilities.TRANSPORT_CELLULAR)
        shadow.setNetworkCapabilities(network, caps)
        shadow.setActiveNetworkInfo(info)
        shadow.setDefaultNetworkActive(true)

        assertFalse(AppUpdater.isOnWifi(context))
    }
}
