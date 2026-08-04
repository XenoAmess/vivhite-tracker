package com.bilibili.livemonitor.util

import android.content.ClipData
import android.content.ContentResolver
import android.content.Intent
import android.net.Uri

/**
 * 图片分享 Intent 工厂：ACTION_SEND + EXTRA_STREAM + ClipData 授权。
 * MainActivity 的魔法期图 / 图文 / 宣传图三处分享共用，避免同款组装重复三份。
 */
object ShareImageFactory {

    /**
     * @param uri FileProvider 授权的图片 uri
     * @param contentResolver 用于 ClipData.newUri 授权（部分目标只认 ClipData 才读得到流）
     * @param clipLabel ClipData 标签（区分来源）
     * @param mimeType 图片 MIME（默认 image/任意子类型，如 image/png）
     * @param extraText 附带文案（尊重它的应用如微博/邮件）
     * @param extraSubject 附主题（可选）
     */
    fun buildImageShareIntent(
        uri: Uri,
        contentResolver: ContentResolver,
        clipLabel: String,
        mimeType: String = "image/*",
        extraText: String? = null,
        extraSubject: String? = null
    ): Intent = Intent(Intent.ACTION_SEND).apply {
        type = mimeType
        putExtra(Intent.EXTRA_STREAM, uri)
        if (extraSubject != null) putExtra(Intent.EXTRA_SUBJECT, extraSubject)
        if (extraText != null) putExtra(Intent.EXTRA_TEXT, extraText)
        // ClipData 授权：部分目标只认它（不认 intent flag）才读得到图片流
        clipData = ClipData.newUri(contentResolver, clipLabel, uri)
        addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
    }
}
