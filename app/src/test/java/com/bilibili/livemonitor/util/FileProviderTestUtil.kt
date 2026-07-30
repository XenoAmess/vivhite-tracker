package com.bilibili.livemonitor.util

/**
 * Robolectric 全量跑时 FileProvider.sCache（authority → PathStrategy）
 * 跨 sandbox 残留在共享 classloader 里：先跑的测试类把 roots 绑到自己
 * 沙箱的 filesDir，后跑的测试类命中缓存后路径前缀不匹配，
 * 抛 "Failed to find configured root"。
 * 凡是触达 FileProvider.getUriForFile 的用例，开始前必须清一次。
 */
object FileProviderTestUtil {
    fun clearFileProviderCache() {
        val field = androidx.core.content.FileProvider::class.java.getDeclaredField("sCache")
        field.isAccessible = true
        (field.get(null) as MutableMap<*, *>).clear()
    }
}
