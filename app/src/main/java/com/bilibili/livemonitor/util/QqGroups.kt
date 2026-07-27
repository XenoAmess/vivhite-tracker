package com.bilibili.livemonitor.util

import android.content.Intent
import android.net.Uri
import androidx.annotation.DrawableRes
import com.bilibili.livemonitor.R

/**
 * QQ 交流群配置与跳转。
 * 群信息集中在此表，换群/换图只改这里。
 */
object QqGroups {

    data class QqGroup(
        val number: String,
        val displayName: String,
        @DrawableRes val avatarRes: Int
    )

    val groups: List<QqGroup> = listOf(
        QqGroup("774800912", "数学研讨", R.drawable.qq_group_1),
        QqGroup("775455331", "游戏联机", R.drawable.qq_group_2),
        QqGroup("292901300", "慕白者琉", R.drawable.qq_group_3),
    )

    // mqqapi 群资料卡 scheme（社区主流格式，多源验证），打开后可点「加入」
    fun groupCardIntent(group: QqGroup, qqPackage: String?): Intent {
        val url = "mqqapi://card/show_pslcard?src_type=internal&version=1" +
            "&uin=${group.number}&card_type=group&source=qrcode"
        return Intent(Intent.ACTION_VIEW, Uri.parse(url)).apply {
            // setPackage 强制投递给探测到的 QQ 客户端（同 bilibili 的包可见性策略）
            if (!qqPackage.isNullOrEmpty()) setPackage(qqPackage)
        }
    }
}
