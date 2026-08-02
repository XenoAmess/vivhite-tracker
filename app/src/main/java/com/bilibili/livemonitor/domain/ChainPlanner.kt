package com.bilibili.livemonitor.domain

// 增量更新方案选择：纯函数。输入 version.json 元数据 + 本地底包校验结果，
// 输出走增量链还是全量下载。所有"不能增量"的场景都安全回退全量
object ChainPlanner {

    sealed class UpdatePlan {
        // 按链逐跳下载补丁并打补丁
        data class Incremental(val chain: UpdateDecider.UpdateChain) : UpdatePlan()
        // 全量下载 APK（现状路径）
        object FullApk : UpdatePlan()
    }

    /**
     * @param chain 当前 versionCode 在 version.json chains 里查到的链（null=无链）
     * @param localApkSha256 本地已安装 APK 的 sha256（null=读取失败）
     * @param remoteApkSize 目标全量 APK 大小（增量总大小不小于它就没必要增量）
     */
    fun choosePlan(
        chain: UpdateDecider.UpdateChain?,
        localApkSha256: String?,
        remoteApkSize: Long
    ): UpdatePlan {
        if (chain == null) return UpdatePlan.FullApk
        if (chain.hops.isEmpty()) return UpdatePlan.FullApk
        // 底包对不上（beta/本地构建混装等）→ 打了也是废的，必须全量
        if (localApkSha256 == null ||
            !localApkSha256.equals(chain.fromApkSha256, ignoreCase = true)
        ) {
            return UpdatePlan.FullApk
        }
        // 增量总下载量不小于全量（远古版本跨度太大）→ 直接全量
        if (remoteApkSize > 0 && chain.totalSize >= remoteApkSize) {
            return UpdatePlan.FullApk
        }
        return UpdatePlan.Incremental(chain)
    }
}
