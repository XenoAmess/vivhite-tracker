package com.bilibili.livemonitor.domain

import java.net.URI

/**
 * GitHub 下载加速：把 release 资产 URL 展开为「公共镜像前缀 + 原 URL」候选列表。
 * 完整性不依赖镜像可信：version.json 的 apkSha256 强校验 + 同签名安装兜底，
 * 镜像篡改的包校验不过直接弃用；镜像只影响可用性。
 */
object UpdateMirrors {

    // 公共 GitHub 加速镜像（按优先级），失效时改这里即可
    val MIRROR_PREFIXES = listOf(
        "https://ghfast.top/",
        "https://gh-proxy.com/"
    )

    // 只有这些主机的 https URL 才拼镜像前缀；
    // github.io（beta 通道 Pages）/ http / 本地地址（单测 HttpServer）一律直连
    private val PROXYABLE_HOSTS = setOf("github.com", "objects.githubusercontent.com")

    /** 下载候选：[镜像1+url, 镜像2+url, ..., 原url]；不可代理的 URL 原样返回单元素列表 */
    fun candidates(url: String): List<String> {
        val uri = runCatching { URI(url) }.getOrNull() ?: return listOf(url)
        if (uri.scheme != "https" || uri.host !in PROXYABLE_HOSTS) return listOf(url)
        return MIRROR_PREFIXES.map { it + url } + url
    }
}
