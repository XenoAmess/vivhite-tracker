package com.bilibili.livemonitor.controller

import android.content.Intent
import android.os.Bundle
import android.widget.Toast
import com.bilibili.livemonitor.MainActivity
import com.bilibili.livemonitor.service.LiveCheckService
import com.bilibili.livemonitor.util.AppLogger
import com.bilibili.livemonitor.util.QqShare
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull

/**
 * 分享编排（从 MainActivity 抽出）：QQ 卡片 / QQ空间 / 图文 / 长宣传图 四路分享 + 授权/兜底。
 *
 * 设计约束：MainActivity 持有全部 internal seam（qqSdkSharer/roomInfoFetcher/faceFetcher/
 * shareImageLoader/coverDownloader/coverBitmapDownloader/promoRenderDispatcher/preferenceManager），
 * 测试经 activity.seam 注入 fake；本控制器经 [activity] 读取，内部分享入口方法在
 * MainActivity 保留为一行委托，保证既有测试（activity.shareAsImageText() 等）不受影响。
 */
class ShareController(private val activity: MainActivity) {

    // 分享协程作用域：独立于 Activity 主 scope，onDestroy 时经 cancel() 取消
    val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())

    // 当前分享用的实时状态（fetch 成功用 API 的；失败回退本地缓存，供文案/兜底共用）
    private var currentShareLive: Boolean = false
    private var currentShareTitle: String? = null

    fun cancel() {
        scope.cancel()
    }

    // SDK 回调守卫：QQ 授权/分享回调不受 scope 管理，Activity 可能已被销毁（转屏/返回）
    private inline fun guardUi(block: () -> Unit) {
        if (activity.isFinishing || activity.isDestroyed) return
        block()
    }

    // 分享入口统一取直播间信息：3s 超时兜底（超时只是丢弃结果，见 activity.roomInfoFetcher 注释）
    private suspend fun fetchShareRoomInfo(): com.bilibili.livemonitor.api.BilibiliApi.RoomInfo? =
        withTimeoutOrNull(3000) { activity.roomInfoFetcher(QqShare.ROOM_ID) }

    /**
     * 分享文案的开播状态：fetch 成功用 API 实时状态（分享永远新鲜）；
     * fetch 失败回退本地缓存（监控中的服务状态，否则上次成功检测值）。
     */
    private fun resolveShareLiveState(roomInfo: com.bilibili.livemonitor.api.BilibiliApi.RoomInfo?): Boolean =
        when {
            roomInfo != null -> roomInfo.live
            LiveCheckService.isRunning -> LiveCheckService.lastLiveStatus
            else -> activity.preferenceManager.isLastCheckSuccess() &&
                activity.preferenceManager.isLastCheckLive()
        }

    fun shareLiveRoom() {
        Toast.makeText(activity, "正在生成分享卡片…", Toast.LENGTH_SHORT).show()
        // 注入 applicationContext 给 DefaultQqSdkSharer（isAuthorized/login 需要）
        (activity.qqSdkSharer as? com.bilibili.livemonitor.util.DefaultQqSdkSharer)?.bind(activity.applicationContext)
        scope.launch {
            val roomInfo = fetchShareRoomInfo()
            val title = roomInfo?.title
            val isLive = resolveShareLiveState(roomInfo)
            // 缩略图策略：开播=直播封面（内容优先）；
            // 未开播/封面缺失=白绮方形头像（QQ 卡片缩略图按方形裁，16:9 封面会被切边）
            val cover = if (isLive && roomInfo?.cover != null) {
                roomInfo.cover
            } else {
                withTimeoutOrNull(3000) {
                    activity.faceFetcher(com.bilibili.livemonitor.util.BiliTargets.MONITOR_MID)
                } ?: QqShare.FALLBACK_COVER_URL
            }
            AppLogger.d("MainActivity", "share cover=$cover title=$title live=$isLive")
            currentShareTitle = title
            currentShareLive = isLive
            val params = QqShare.buildSdkShareParams(cover, title, isLive)
            doQqShare(params)
        }
    }

    /**
     * 图文分享：状态感知文案（EXTRA_TEXT）+ 直播间封面（EXTRA_STREAM）。
     * 预研结论：QQ/微信/TIM 等聊天类应用收图片分享必丢 EXTRA_TEXT——
     * 所以文案同时烙进封面底部（renderCaptionedCover），任何目标都丢不了；
     * EXTRA_TEXT/EXTRA_SUBJECT/ClipData 保留给尊重它们的应用（微博/邮件，双保险）。
     */
    fun shareAsImageText() {
        Toast.makeText(activity, "正在准备图文分享…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val roomInfo = fetchShareRoomInfo()
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            currentShareTitle = title
            currentShareLive = isLive
            val decider = com.bilibili.livemonitor.domain.ShareTextDecider
            val caption = decider.body(isLive, QqShare.ROOM_ID, title)
            val coverBitmap = roomInfo?.cover?.let {
                withContext(Dispatchers.IO) {
                    activity.coverBitmapDownloader(it)
                }
            }
            // 文案烙进封面底部半透明条带
            val captioned = coverBitmap?.let {
                com.bilibili.livemonitor.util.PromoImageRenderer.renderCaptionedCover(it, caption)
            }
            val file = captioned?.let {
                withContext(Dispatchers.IO) {
                    activity.shareImageLoader.save(activity, it, "cover_caption.png")
                }
            }
            if (file == null) {
                // 封面拿不到时降级纯文本，状态文案仍然准确
                AppLogger.w("MainActivity", "image-text share: cover unavailable, fallback to text")
                fallbackToSystemShare()
                return@launch
            }
            val uri = activity.shareImageLoader.shareableUri(activity, file)
            val intent = com.bilibili.livemonitor.util.ShareImageFactory.buildImageShareIntent(
                uri = uri,
                contentResolver = activity.contentResolver,
                clipLabel = "cover",
                mimeType = "image/*",
                extraSubject = decider.title(isLive, title),
                extraText = "$caption ${QqShare.buildShareUrl()}"
            )
            activity.startActivity(Intent.createChooser(intent, "图文分享（部分应用可能只发图片）"))
        }
    }

    /**
     * QQ空间图文说说：官方图文通道（TYPE_IMAGE_TEXT：文案+封面+链接俱全）。
     * 授权流程与 QQ 卡片共用同一 Tencent session。
     */
    fun shareAsQzone() {
        Toast.makeText(activity, "正在准备说说…", Toast.LENGTH_SHORT).show()
        (activity.qqSdkSharer as? com.bilibili.livemonitor.util.DefaultQqSdkSharer)?.bind(activity.applicationContext)
        scope.launch {
            val roomInfo = fetchShareRoomInfo()
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            // QzoneShare 只收本地路径，封面先下载落盘
            val coverFile = roomInfo?.cover?.let {
                withContext(Dispatchers.IO) {
                    activity.coverDownloader(it)
                }
            }
            val params = QqShare.buildQzoneShareParams(coverFile?.absolutePath, title, isLive)
            AppLogger.d("MainActivity", "shareAsQzone isAuthorized=${activity.qqSdkSharer.isAuthorized()}")
            if (activity.qqSdkSharer.isAuthorized()) {
                doQzoneShareAfterAuthorized(params)
            } else {
                showQqAuthGuideDialog(params) { doQzoneShareAfterAuthorized(params) }
            }
        }
    }

    private fun doQzoneShareAfterAuthorized(params: Bundle) {
        activity.qqSdkSharer.shareToQzone(
            activity = activity,
            params = params,
            onComplete = {
                guardUi {
                    Toast.makeText(activity, "已分享到 QQ 空间", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = {
                AppLogger.d("MainActivity", "qzone share cancelled by user")
            },
            onError = { code, msg ->
                guardUi {
                    AppLogger.e("MainActivity", "qzone share onError: code=$code msg=$msg")
                    if (code == com.bilibili.livemonitor.util.QQ_ERR_USER_NOT_AUTHORIZED) {
                        // session 过期：重新弹授权引导
                        showQqAuthGuideDialog(params) { doQzoneShareAfterAuthorized(params) }
                    } else {
                        Toast.makeText(activity, "说说分享失败：$msg", Toast.LENGTH_LONG).show()
                    }
                }
            }
        )
    }

    /**
     * 生成宣传图：封面+状态文案+直播间二维码全部烙进图里，
     * 任何分享目标都不丢文案（预研：QQ/微信会丢 EXTRA_TEXT）。
     * 先弹预览对话框，三风格即时切换（选择持久化），点分享才落盘发出。
     */
    fun shareAsPromoImage() {
        Toast.makeText(activity, "正在生成宣传图…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val roomInfo = fetchShareRoomInfo()
            val isLive = resolveShareLiveState(roomInfo)
            val title = roomInfo?.title
            val coverBitmap = roomInfo?.cover?.let {
                withContext(Dispatchers.IO) {
                    activity.coverBitmapDownloader(it)
                }
            }
            val headline = if (isLive && !title.isNullOrBlank()) "白绮开播啦！「$title」"
                           else com.bilibili.livemonitor.domain.ShareTextDecider.title(isLive, title)
            val body = com.bilibili.livemonitor.domain.ShareTextDecider.body(isLive, QqShare.ROOM_ID, title)
            showPromoPreview(coverBitmap, headline, body, isLive)
        }
    }

    /**
     * 宣传图预览对话框：53 种风格 chip 列表，点切换即时重渲染，选择持久化，点「分享」才落盘发出。
     * chip 用色点 + 名字 3 列网格（RecyclerView + GridLayoutManager）。
     */
    fun showPromoPreview(
        cover: android.graphics.Bitmap?,
        headline: String,
        body: String,
        isLive: Boolean = false
    ) {
        val view = activity.layoutInflater.inflate(com.bilibili.livemonitor.R.layout.dialog_promo_preview, null)
        val iv = view.findViewById<android.widget.ImageView>(com.bilibili.livemonitor.R.id.ivPromoPreview)
        val rv = view.findViewById<androidx.recyclerview.widget.RecyclerView>(com.bilibili.livemonitor.R.id.rvPromoStyles)
        val shareButton = view.findViewById<com.google.android.material.button.MaterialButton>(com.bilibili.livemonitor.R.id.btnPromoShare)
        val dialog = android.app.AlertDialog.Builder(activity).setView(view).create()
        shareButton.isEnabled = false

        val allStyles = com.bilibili.livemonitor.util.PromoImageRenderer.Style.values()
        val initial: com.bilibili.livemonitor.util.PromoImageRenderer.Style = runCatching {
            com.bilibili.livemonitor.util.PromoImageRenderer.Style.valueOf(activity.preferenceManager.getPromoStyle())
        }.getOrNull() ?: com.bilibili.livemonitor.util.PromoImageRenderer.Style.LIGHT_CARD
        var current: com.bilibili.livemonitor.util.PromoImageRenderer.Style = initial
        var bitmap: android.graphics.Bitmap? = null
        var renderJob: kotlinx.coroutines.Job? = null
        var renderGeneration = 0
        var disposed = false

        fun rerender() {
            rv.adapter?.notifyDataSetChanged()
            shareButton.isEnabled = false
            val requestedStyle = current
            val generation = ++renderGeneration
            renderJob?.cancel()
            renderJob = scope.launch {
                // Keep ownership until the coroutine has resumed successfully: Canvas rendering is
                // non-cooperative, so a cancelled render can otherwise orphan its native bitmap.
                var rendered: android.graphics.Bitmap? = null
                try {
                    withContext(activity.promoRenderDispatcher) {
                        rendered = com.bilibili.livemonitor.util.PromoImageRenderer.render(
                            requestedStyle, cover, headline, body, isLive
                        )
                    }
                    val currentBitmap = rendered ?: return@launch
                    if (disposed || generation != renderGeneration) return@launch
                    val previous = bitmap
                    bitmap = currentBitmap
                    rendered = null
                    iv.setImageBitmap(currentBitmap)
                    previous?.recycle()
                    shareButton.isEnabled = true
                } finally {
                    rendered?.recycle()
                }
            }
        }

        val names = activity.resources.getStringArray(com.bilibili.livemonitor.R.array.promo_style_names)
        rv.layoutManager = androidx.recyclerview.widget.GridLayoutManager(activity, 3)
        rv.adapter = object : androidx.recyclerview.widget.RecyclerView.Adapter<androidx.recyclerview.widget.RecyclerView.ViewHolder>() {
            override fun onCreateViewHolder(parent: android.view.ViewGroup, viewType: Int) =
                object : androidx.recyclerview.widget.RecyclerView.ViewHolder(
                    activity.layoutInflater.inflate(com.bilibili.livemonitor.R.layout.item_promo_style_chip, parent, false)
                ) {}

            override fun onBindViewHolder(holder: androidx.recyclerview.widget.RecyclerView.ViewHolder, position: Int) {
                val style = allStyles[position]
                val item = holder.itemView
                val dot = item.findViewById<android.view.View>(com.bilibili.livemonitor.R.id.vChipDot)
                val name = item.findViewById<android.widget.TextView>(com.bilibili.livemonitor.R.id.tvChipName)
                val selected = style == current
                // 圆形色点；选中态 = 紫圈描边 + 名字加粗（不占宽度，4 字名不截断）
                dot.background = android.graphics.drawable.GradientDrawable().apply {
                    shape = android.graphics.drawable.GradientDrawable.OVAL
                    setColor(com.bilibili.livemonitor.util.PromoImageRenderer.chipColorOf(style))
                    if (selected) setStroke(4, 0xFF6750A4.toInt())
                    else setStroke(1, 0x1A000000)
                }
                name.text = names[position]
                name.setTypeface(name.typeface, if (selected) android.graphics.Typeface.BOLD else android.graphics.Typeface.NORMAL)
                item.setOnClickListener {
                    if (current != style) {
                        current = style
                        activity.preferenceManager.setPromoStyle(style.name)
                        rerender()
                    }
                }
            }

            override fun getItemCount(): Int = allStyles.size
        }
        dialog.setOnDismissListener {
            disposed = true
            renderGeneration++
            renderJob?.cancel()
            bitmap?.recycle()
            bitmap = null
        }
        dialog.show()
        rerender()

        shareButton.setOnClickListener {
            val bmp = bitmap ?: return@setOnClickListener
            // 分享协程接管位图所有权，dialog dismiss 不得抢先 recycle。
            bitmap = null
            dialog.dismiss()
            sharePromoBitmap(bmp, body)
        }
        view.findViewById<com.google.android.material.button.MaterialButton>(com.bilibili.livemonitor.R.id.btnPromoCancel)
            .setOnClickListener { dialog.dismiss() }
    }

    private fun sharePromoBitmap(promo: android.graphics.Bitmap, body: String) {
        Toast.makeText(activity, "正在准备分享…", Toast.LENGTH_SHORT).show()
        scope.launch {
            val file = withContext(Dispatchers.IO) {
                activity.shareImageLoader.save(activity, promo, "promo.png")
            }
            promo.recycle()
            if (file == null) {
                Toast.makeText(activity, "宣传图生成失败", Toast.LENGTH_LONG).show()
                return@launch
            }
            val uri = activity.shareImageLoader.shareableUri(activity, file)
            val intent = com.bilibili.livemonitor.util.ShareImageFactory.buildImageShareIntent(
                uri = uri,
                contentResolver = activity.contentResolver,
                clipLabel = "promo",
                mimeType = "image/png",
                extraText = "$body ${QqShare.buildShareUrl()}"
            )
            activity.startActivity(Intent.createChooser(intent, "分享宣传图"))
        }
    }

    private fun doQqShare(params: Bundle) {
        AppLogger.d("MainActivity", "doQqShare isAuthorized=${activity.qqSdkSharer.isAuthorized()}")
        if (activity.qqSdkSharer.isAuthorized()) {
            // 已授权：直接走真卡片
            doQqShareAfterAuthorized(params)
        } else {
            // 未授权：弹引导对话框让用户选「去授权」或「普通分享」
            showQqAuthGuideDialog(params)
        }
    }

    private fun showQqAuthGuideDialog(
        params: Bundle,
        onAuthorizedProceed: () -> Unit = { doQqShareAfterAuthorized(params) }
    ) {
        androidx.appcompat.app.AlertDialog.Builder(activity)
            .setTitle("QQ 分享需要先授权")
            .setMessage(
                "首次分享到 QQ 需要先在 QQ 端授权「牢白播了吗」使用 QQ 互联能力。\n\n" +
                "点「去 QQ 授权」完成授权后，下次即可使用真卡片分享。\n" +
                "点「普通分享」可用纯文本分享（无封面）。"
            )
            .setPositiveButton("去 QQ 授权") { d, _ ->
                d.dismiss()
                activity.qqSdkSharer.login(
                    activity = activity,
                    onAuthorized = {
                        guardUi {
                            AppLogger.d("MainActivity", "qq auth completed, proceed to share")
                            Toast.makeText(activity, "QQ 授权成功", Toast.LENGTH_SHORT).show()
                            onAuthorizedProceed()
                        }
                    },
                    onCancelled = {
                        guardUi {
                            Toast.makeText(activity, "已取消授权", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { code, msg ->
                        guardUi {
                            AppLogger.e("MainActivity", "qq auth failed: code=$code msg=$msg")
                            Toast.makeText(activity, "QQ 授权失败：$msg", Toast.LENGTH_LONG).show()
                            // 授权失败也兜底走系统分享，用户至少能分享出去
                            fallbackToSystemShare()
                        }
                    }
                )
            }
            .setNegativeButton("普通分享") { dialog, _ ->
                dialog.dismiss()
                fallbackToSystemShare()
            }
            .setCancelable(true)
            .show()
    }

    private fun doQqShareAfterAuthorized(params: Bundle) {
        activity.qqSdkSharer.shareToQQ(
            activity = activity,
            params = params,
            onComplete = {
                guardUi {
                    Toast.makeText(activity, "已分享到 QQ", Toast.LENGTH_SHORT).show()
                }
            },
            onCancel = {
                AppLogger.d("MainActivity", "qq share cancelled by user")
            },
            onError = { code, msg ->
                guardUi {
                    AppLogger.e("MainActivity", "qq share onError: code=$code msg=$msg")
                    when (code) {
                        com.bilibili.livemonitor.util.QQ_ERR_USER_NOT_AUTHORIZED -> {
                            // session 过期/失效（罕见，但可能发生）：重新弹引导
                            AppLogger.w("MainActivity", "qq session expired unexpectedly, re-prompt")
                            showQqAuthGuideDialog(params)
                        }
                        else -> {
                            Toast.makeText(activity, "分享失败：$msg", Toast.LENGTH_LONG).show()
                            fallbackToSystemShare()
                        }
                    }
                }
            }
        )
    }

    private fun fallbackToSystemShare() {
        AppLogger.d("MainActivity", "fallback to system share")
        activity.startActivity(
            Intent.createChooser(
                QqShare.buildSystemShareIntent(currentShareTitle, currentShareLive),
                "分享直播间"
            )
        )
    }

    // 最终兜底：把带 bbid 归因的分享链接复制到剪贴板
    fun copyShareLinkToClipboard() {
        val clipboard = activity.getSystemService(android.content.Context.CLIPBOARD_SERVICE) as android.content.ClipboardManager
        clipboard.setPrimaryClip(
            android.content.ClipData.newPlainText("bilibili_live", QqShare.buildShareUrl())
        )
        Toast.makeText(activity, "链接已复制到剪贴板", Toast.LENGTH_LONG).show()
    }
}
