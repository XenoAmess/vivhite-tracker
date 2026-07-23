package com.bilibili.livemonitor.util

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import java.util.Locale

object OemHelper {

    data class OemInfo(
        val displayName: String,
        val guideText: String,
        val intents: List<Intent>
    )

    fun isProblematicOem(): Boolean {
        return getOemInfo() != null
    }

    fun getOemInfo(): OemInfo? {
        val manufacturer = Build.MANUFACTURER.lowercase(Locale.US)
        return when {
            manufacturer.contains("xiaomi") || manufacturer.contains("redmi") -> OemInfo(
                displayName = "小米 (MIUI)",
                guideText = "MIUI 需要额外设置才能保证后台监控：\n\n" +
                    "1. 开启「自启动」\n" +
                    "2. 省电策略设为「无限制」\n" +
                    "3. 在最近任务中锁定本应用",
                intents = listOf(
                    // 自启动管理页
                    Intent().setComponent(
                        ComponentName(
                            "com.miui.securitycenter",
                            "com.miui.permcenter.autostart.AutoStartManagementActivity"
                        )
                    ),
                    // 应用详情页（兜底）
                    appDetailIntent()
                )
            )
            manufacturer.contains("oppo") || manufacturer.contains("realme") -> OemInfo(
                displayName = "OPPO (ColorOS)",
                guideText = "ColorOS 需要额外设置才能保证后台监控：\n\n" +
                    "1. 开启「允许自启动」\n" +
                    "2. 电池管理设为「允许后台运行」\n" +
                    "3. 在最近任务中锁定本应用",
                intents = listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.coloros.safecenter",
                            "com.coloros.safecenter.permission.startup.StartupAppListActivity"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.oplus.safecenter",
                            "com.oplus.safecenter.permission.startup.StartupAppListActivity"
                        )
                    ),
                    appDetailIntent()
                )
            )
            manufacturer.contains("vivo") || manufacturer.contains("iqoo") -> OemInfo(
                displayName = "vivo (FuntouchOS/OriginOS)",
                guideText = "vivo 需要额外设置才能保证后台监控：\n\n" +
                    "1. 开启「自启动」\n" +
                    "2. 后台耗电管理设为「允许后台高耗电」\n" +
                    "3. 在最近任务中锁定本应用",
                intents = listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.iqoo.secure",
                            "com.iqoo.secure.ui.phoneoptimize.AddWhiteListActivity"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.vivo.permissionmanager",
                            "com.vivo.permissionmanager.activity.BgStartUpManagerActivity"
                        )
                    ),
                    appDetailIntent()
                )
            )
            manufacturer.contains("huawei") -> OemInfo(
                displayName = "华为 (EMUI)",
                guideText = "EMUI 需要额外设置才能保证后台监控：\n\n" +
                    "1. 应用启动管理设为「手动管理」并全部允许\n" +
                    "2. 电池优化设为「不允许」\n" +
                    "3. 在最近任务中锁定本应用",
                intents = listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    ),
                    Intent().setComponent(
                        ComponentName(
                            "com.huawei.systemmanager",
                            "com.huawei.systemmanager.appcontrol.activity.StartupAppControlActivity"
                        )
                    ),
                    appDetailIntent()
                )
            )
            manufacturer.contains("honor") -> OemInfo(
                displayName = "荣耀 (MagicOS)",
                guideText = "荣耀需要额外设置才能保证后台监控：\n\n" +
                    "1. 应用启动管理设为「手动管理」并全部允许\n" +
                    "2. 电池优化设为「不允许」\n" +
                    "3. 在最近任务中锁定本应用",
                intents = listOf(
                    Intent().setComponent(
                        ComponentName(
                            "com.hihonor.systemmanager",
                            "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
                        )
                    ),
                    appDetailIntent()
                )
            )
            manufacturer.contains("samsung") -> OemInfo(
                displayName = "三星 (One UI)",
                guideText = "三星需要额外设置才能保证后台监控：\n\n" +
                    "1. 电池用量设为「不受限制」\n" +
                    "2. 从「休眠应用」和「深度休眠应用」列表中移除",
                intents = listOf(
                    appDetailIntent()
                )
            )
            else -> null
        }
    }

    fun openOemSettings(context: Context) {
        val info = getOemInfo() ?: return
        for (intent in info.intents) {
            try {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                context.startActivity(intent)
                AppLogger.d("OemHelper", "opened OEM settings: ${intent.component}")
                return
            } catch (e: Exception) {
                AppLogger.w("OemHelper", "failed to open ${intent.component}: ${e.message}")
            }
        }
        // 全部失败，打开应用详情页
        try {
            val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
                data = Uri.parse("package:${context.packageName}")
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            context.startActivity(fallback)
        } catch (e: Exception) {
            AppLogger.e("OemHelper", "failed to open app details", e)
        }
    }

    private const val PACKAGE_NAME = "com.bilibili.livemonitor"

    private fun appDetailIntent(): Intent {
        return Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.parse("package:$PACKAGE_NAME")
        }
    }
}
