package com.bilibili.livemonitor.controller

import android.content.Intent
import android.net.Uri
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import com.bilibili.livemonitor.BuildConfig
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.R
import com.bilibili.livemonitor.api.UpdateChecker
import com.bilibili.livemonitor.domain.UpdateDecider
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.AppUpdater
import com.bilibili.livemonitor.util.PreferenceManager
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

/**
 * 更新检查/下载/设置（从 MainActivity 拆出）。
 * 测试注入位 `MainActivity.updateChecker` 保留在 Activity 上，这里每次调用时动态读取；
 * 协程作用域由本控制器持有，Activity onDestroy 时调 [cancel]。
 */
class UpdateController(
    private val activity: MainActivity
) {

    private val prefs: PreferenceManager
        get() = activity.preferenceManager

    private val checker: UpdateChecker
        get() = activity.updateChecker

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var downloadJob: Job? = null

    fun cancel() {
        downloadJob?.cancel()
        scope.cancel()
    }

    // 成功检查按日节流；失败跨进程仅退避一小时，避免重启后反复请求，也不阻塞整天。
    fun autoCheckUpdateIfDue(now: Long = System.currentTimeMillis()) {
        if (!prefs.isAutoCheckUpdate()) return
        if (now - prefs.getLastUpdateCheckTime() < UPDATE_CHECK_INTERVAL_MS) return
        val lastAttempt = prefs.getLastUpdateAttemptTime()
        if (lastAttempt != 0L &&
            now - lastAttempt < UPDATE_RETRY_INTERVAL_MS
        ) return
        prefs.setLastUpdateAttemptTime(now)
        checkForUpdate(manual = false, successfulCheckTime = now)
    }

    fun checkForUpdate(manual: Boolean) = checkForUpdate(manual, successfulCheckTime = null)

    private fun checkForUpdate(manual: Boolean, successfulCheckTime: Long?) {
        if (manual) {
            Toast.makeText(activity, "正在检查更新…", Toast.LENGTH_SHORT).show()
        }
        scope.launch {
            when (val state = checker.checkLatestRelease(
                BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
            )) {
                is UpdateDecider.UpdateState.UpdateAvailable -> {
                    successfulCheckTime?.let(prefs::setLastUpdateCheckTime)
                    val info = state.info
                    val dismissed = !manual && info.versionCode == prefs.getDismissedVersionCode()
                    if (dismissed) return@launch
                    if (!manual && prefs.isAutoDownloadUpdate() && AppUpdater.isOnWifi(activity)) {
                        startUpdateDownload(info)
                    } else {
                        showUpdateDialog(info)
                    }
                }
                UpdateDecider.UpdateState.UpToDate -> {
                    successfulCheckTime?.let(prefs::setLastUpdateCheckTime)
                    if (manual) Toast.makeText(activity, "已是最新版本", Toast.LENGTH_SHORT).show()
                }
                is UpdateDecider.UpdateState.Error -> {
                    AppLogger.w("UpdateController", "update check failed: ${state.reason}")
                    if (manual) showUpdateErrorDialog(state.reason)
                }
            }
        }
    }

    // 检查失败不再只有一个 Toast：区分网络错误/发布页格式，给出 Releases 页出口
    fun showUpdateErrorDialog(reason: String) {
        val message = if (reason == "network error") {
            "无法连接 GitHub，请检查网络后重试"
        } else {
            "暂时无法获取更新信息（$reason）"
        }
        AlertDialog.Builder(activity)
            .setTitle("检查更新失败")
            .setMessage(message)
            .setPositiveButton("打开发布页") { _, _ ->
                try {
                    activity.startActivity(
                        Intent(Intent.ACTION_VIEW, Uri.parse("$GITHUB_URL/releases/latest"))
                    )
                } catch (e: Exception) {
                    AppLogger.e("UpdateController", "open releases page failed", e)
                }
            }
            .setNegativeButton("取消", null)
            .show()
    }

    /**
     * 内测版尝鲜：比对 GitHub Pages 上的 master 最新构建，比本地新则下载更新。
     * 手动触发，无忽略版本/自动下载逻辑；versionCode 比较天然防降级。
     */
    fun checkBetaUpdate() {
        Toast.makeText(activity, "正在检查内测版…", Toast.LENGTH_SHORT).show()
        scope.launch {
            when (val state = checker.checkBetaChannel(
                BuildConfig.VERSION_CODE, BuildConfig.VERSION_NAME
            )) {
                is UpdateDecider.UpdateState.UpdateAvailable -> showUpdateDialog(state.info)
                UpdateDecider.UpdateState.UpToDate ->
                    Toast.makeText(activity, "已是最新内测版", Toast.LENGTH_SHORT).show()
                is UpdateDecider.UpdateState.Error -> {
                    AppLogger.w("UpdateController", "beta update check failed: ${state.reason}")
                    showUpdateErrorDialog(state.reason)
                }
            }
        }
    }

    fun showUpdateDialog(info: UpdateDecider.ReleaseInfo) {
        val isBeta = info.tagName == UpdateChecker.BETA_TAG_NAME
        val title = if (isBeta) "发现内测版 v${info.versionName}" else "发现新版本 v${info.versionName}"
        val builder = AlertDialog.Builder(activity)
            .setTitle(title)
            .setMessage(info.changelog.trim().ifBlank { "暂无更新说明" }.take(500))
            .setPositiveButton("立即更新") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("取消", null)
        if (!isBeta) {
            // 「忽略此版本」只对正式通道有意义：beta 的 versionCode 与 stable 的
            // dismissed 逻辑互不相关，避免污染
            builder.setNeutralButton("忽略此版本") { _, _ ->
                prefs.setDismissedVersionCode(info.versionCode)
            }
        }
        builder.show()
    }

    fun startUpdateDownload(info: UpdateDecider.ReleaseInfo) {
        if (downloadJob?.isActive == true) {
            Toast.makeText(activity, "更新包正在下载", Toast.LENGTH_SHORT).show()
            return
        }
        if (!AppUpdater.canRequestInstalls(activity)) {
            AlertDialog.Builder(activity)
                .setTitle("需要安装权限")
                .setMessage("系统要求授予「安装未知应用」权限后才能更新。开启后请重新点击检查更新。")
                .setPositiveButton("去开启") { _, _ ->
                    try {
                        activity.startActivity(AppUpdater.unknownSourcesIntent(activity))
                    } catch (e: Exception) {
                        AppLogger.e("UpdateController", "open unknown sources settings failed", e)
                        activity.openAppDetails()
                    }
                }
                .setNegativeButton("取消", null)
                .show()
            return
        }
        val dialogView = activity.layoutInflater.inflate(R.layout.dialog_update_progress, null)
        val bar = dialogView.findViewById<android.widget.ProgressBar>(R.id.updateProgressBar)
        val label = dialogView.findViewById<android.widget.TextView>(R.id.tvUpdateProgressLabel)
        val dialog = AlertDialog.Builder(activity)
            .setTitle("正在下载更新 v${info.versionName}")
            .setView(dialogView)
            .setNegativeButton("取消", null)
            .create()
        val dest = AppUpdater.apkFile(activity, info.versionName)
        dialog.show()
        label.text = "正在准备下载…"

        val job = scope.launch(start = CoroutineStart.LAZY) {
            processDownloadMutex.withLock {
            AppUpdater.cleanupOldDownloads(activity, keepApk = dest, apkMaxAgeMs = 0L)
            try {
                // 增量优先：有链且底包 sha256 匹配且比全量小才走补丁，任一失败回退全量
                val localApkSha = withContext(Dispatchers.IO) {
                    com.bilibili.livemonitor.util.ApkPatcher.installedApkFile(activity)
                        ?.let { com.bilibili.livemonitor.util.ApkPatcher.sha256(it) }
                }
                val plan = com.bilibili.livemonitor.domain.ChainPlanner.choosePlan(
                    info.chain, localApkSha, info.apkSize
                )
                var readyApk: java.io.File? = null
                if (plan is com.bilibili.livemonitor.domain.ChainPlanner.UpdatePlan.Incremental) {
                    AppLogger.d("UpdateController", "incremental update: ${plan.chain.hops.size} hop(s), total ${plan.chain.totalSize} bytes")
                    val updater = com.bilibili.livemonitor.util.IncrementalUpdater(activity)
                    updater.downloader = { url, d, cb -> checker.downloadApk(url, d, cb) }
                    readyApk = updater.executeChain(plan.chain, info.versionName) { percent ->
                        bar.post {
                            bar.progress = percent
                            label.text = "正在下载增量包：$percent%"
                        }
                    }
                    if (readyApk == null) {
                        AppLogger.w("UpdateController", "incremental update failed, fallback to full apk")
                        label.text = "增量更新不可用，正在重试完整包…"
                    }
                }

                if (readyApk == null) {
                    label.text = "正在连接下载源…"
                    val ok = checker.downloadApk(info.apkUrl, dest) { percent ->
                        bar.post {
                            bar.progress = percent
                            label.text = "正在下载完整包：$percent%"
                        }
                    }
                    label.text = "正在校验更新包…"
                    // 全量包完整性校验（version.json 带 apkSha256 时）
                    val verified = ok && info.apkSha256?.let { expect ->
                        withContext(Dispatchers.IO) {
                            com.bilibili.livemonitor.util.ApkPatcher.sha256(dest)
                        }.equals(expect, ignoreCase = true).also { match ->
                            if (!match) {
                                AppLogger.w("UpdateController", "full apk sha256 mismatch, deleted")
                                dest.delete()
                            }
                        }
                    } ?: ok
                    if (verified) readyApk = dest
                }

                dialog.dismiss()
                if (readyApk != null) {
                    try {
                        activity.startActivity(AppUpdater.buildInstallIntent(activity, readyApk!!))
                    } catch (e: Exception) {
                        AppLogger.e("UpdateController", "launch apk installer failed", e)
                        Toast.makeText(activity, "无法打开安装器", Toast.LENGTH_SHORT).show()
                    }
                } else {
                    Toast.makeText(activity, "更新包下载失败，请稍后再试", Toast.LENGTH_SHORT).show()
                    showDownloadFailure(info)
                }
            } catch (_: CancellationException) {
                dest.delete()
                AppUpdater.cleanupOldDownloads(activity, apkMaxAgeMs = 0L)
                dialog.dismiss()
                Toast.makeText(activity, "已取消更新下载", Toast.LENGTH_SHORT).show()
            } catch (e: Exception) {
                AppLogger.e("UpdateController", "update download failed", e)
                dest.delete()
                dialog.dismiss()
                Toast.makeText(activity, "更新包下载失败，请稍后再试", Toast.LENGTH_SHORT).show()
                showDownloadFailure(info)
            } finally {
                if (downloadJob === coroutineContext[Job]) {
                    downloadJob = null
                }
            }
            }
        }
        downloadJob = job
        dialog.setOnCancelListener { job.cancel() }
        dialog.getButton(AlertDialog.BUTTON_NEGATIVE).setOnClickListener {
            label.text = "正在取消并清理临时文件…"
            it.isEnabled = false
            job.cancel()
        }
        job.start()
    }

    private fun showDownloadFailure(info: UpdateDecider.ReleaseInfo) {
        AlertDialog.Builder(activity)
            .setTitle("更新下载失败")
            .setMessage("临时文件已清理。请检查网络后重试。")
            .setPositiveButton("重试") { _, _ -> startUpdateDownload(info) }
            .setNegativeButton("稍后再说", null)
            .show()
    }

    fun showUpdateSettingsDialog() {
        val view = activity.layoutInflater.inflate(R.layout.dialog_update_settings, null)
        val switchAutoCheck = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoCheck)
        val switchAutoDownload = view.findViewById<com.google.android.material.switchmaterial.SwitchMaterial>(R.id.switchAutoDownload)
        switchAutoCheck.isChecked = prefs.isAutoCheckUpdate()
        switchAutoDownload.isChecked = prefs.isAutoDownloadUpdate()
        switchAutoCheck.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoCheckUpdate(isChecked)
        }
        switchAutoDownload.setOnCheckedChangeListener { _, isChecked ->
            prefs.setAutoDownloadUpdate(isChecked)
        }
        AlertDialog.Builder(activity)
            .setTitle("更新设置")
            .setView(view)
            .setPositiveButton("完成", null)
            .show()
    }

    fun computeUpdateSubtitle(): String {
        val check = if (prefs.isAutoCheckUpdate()) "开" else "关"
        val dl = if (prefs.isAutoDownloadUpdate()) "开" else "关"
        return "自动检查: $check  自动下载: $dl"
    }

    companion object {
        // 自动检查更新的最小间隔：24 小时
        internal const val UPDATE_CHECK_INTERVAL_MS = 24 * 3600 * 1000L
        internal const val UPDATE_RETRY_INTERVAL_MS = 60 * 60 * 1000L
        private const val GITHUB_URL = "https://github.com/XenoAmess/vivhite-tracker"
        private val processDownloadMutex = Mutex()
    }
}
