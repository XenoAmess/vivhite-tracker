package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Test

class UpdateMirrorsTest {

    private val apkUrl =
        "https://github.com/XenoAmess/vivhite-tracker/releases/download/v1.0.0/vivhite-tracker-1.0.0.apk"

    @Test
    fun `github 资产 URL 展开为镜像加直连`() {
        val candidates = UpdateMirrors.candidates(apkUrl)
        assertEquals(UpdateMirrors.MIRROR_PREFIXES.size + 1, candidates.size)
        // 镜像按优先级在前，原 URL 垫底兜底
        UpdateMirrors.MIRROR_PREFIXES.forEachIndexed { i, prefix ->
            assertEquals(prefix + apkUrl, candidates[i])
        }
        assertEquals(apkUrl, candidates.last())
    }

    @Test
    fun `objects 域名也代理`() {
        val url = "https://objects.githubusercontent.com/some/asset"
        val candidates = UpdateMirrors.candidates(url)
        assertEquals(url, candidates.last())
        assertEquals(UpdateMirrors.MIRROR_PREFIXES.size + 1, candidates.size)
    }

    @Test
    fun `github io 与 http 与本地地址不代理`() {
        // beta 通道 Pages：ghproxy 类不支持 github.io
        assertEquals(
            listOf("https://xenoamess.github.io/vivhite-tracker/beta/vivhite-tracker-beta.apk"),
            UpdateMirrors.candidates("https://xenoamess.github.io/vivhite-tracker/beta/vivhite-tracker-beta.apk")
        )
        // 单测本地 HttpServer / 任何 http 直连
        assertEquals(
            listOf("http://127.0.0.1:8080/a.apk"),
            UpdateMirrors.candidates("http://127.0.0.1:8080/a.apk")
        )
        // 其他主机不代理
        assertEquals(
            listOf("https://example.com/a.apk"),
            UpdateMirrors.candidates("https://example.com/a.apk")
        )
    }

    @Test
    fun `非法 URL 原样返回`() {
        assertEquals(listOf("not a url"), UpdateMirrors.candidates("not a url"))
    }
}
