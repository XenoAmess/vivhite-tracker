package com.bilibili.livemonitor.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ChainPlannerTest {

    private fun hop(size: Long = 100) = UpdateDecider.PatchHop(
        toVersionCode = 200, url = "https://x/p.bspatch", size = size,
        patchSha256 = "aa", resultSha256 = "bb"
    )

    private fun chain(total: Long = 100, hops: List<UpdateDecider.PatchHop> = listOf(hop())) =
        UpdateDecider.UpdateChain(fromApkSha256 = "deadbeef", totalSize = total, hops = hops)

    @Test
    fun `无链时走全量`() {
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(null, "deadbeef", 1000)
        )
    }

    @Test
    fun `底包sha匹配且增量更小走增量`() {
        val plan = ChainPlanner.choosePlan(chain(total = 300), "DEADBEEF", 1000)
        assertTrue(plan is ChainPlanner.UpdatePlan.Incremental)
        assertEquals(300L, (plan as ChainPlanner.UpdatePlan.Incremental).chain.totalSize)
    }

    @Test
    fun `底包sha不匹配回退全量`() {
        // beta/本地构建混装场景：打了也是废包，必须全量
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(chain(), "other-sha", 1000)
        )
    }

    @Test
    fun `底包sha读取失败回退全量`() {
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(chain(), null, 1000)
        )
    }

    @Test
    fun `增量总大小不小于全量回退全量`() {
        // 远古版本跨度过大：补丁比全量还大，直接全量
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(chain(total = 1000), "deadbeef", 1000)
        )
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(chain(total = 1200), "deadbeef", 1000)
        )
    }

    @Test
    fun `空跳数链回退全量`() {
        assertEquals(
            ChainPlanner.UpdatePlan.FullApk,
            ChainPlanner.choosePlan(chain(hops = emptyList()), "deadbeef", 1000)
        )
    }

    @Test
    fun `远端apkSize未知时不做大小比较`() {
        // apkSize=0（老 version.json）→ 仍可按链走增量
        assertTrue(
            ChainPlanner.choosePlan(chain(total = 500), "deadbeef", 0)
                is ChainPlanner.UpdatePlan.Incremental
        )
    }

    @Test
    fun `跨通道链按本地versionCode命中并走增量`() {
        // stable 装检查 beta（或反向）：chains 只按本地 versionCode 查表，
        // 与来源通道无关；底包 sha 匹配即走增量
        val meta = UpdateDecider.parseVersionMeta(
            """{
                "versionCode": 300,
                "versionName": "1.2.3+4",
                "apkSha256": "cafe",
                "apkSize": 1000,
                "chains": {
                    "200": {
                        "fromApkSha256": "deadbeef",
                        "totalSize": 300,
                        "hops": [
                            {"toVersionCode": 300, "url": "https://x/p.patch", "size": 300,
                             "patchSha256": "aa", "resultSha256": "cafe"}
                        ]
                    }
                }
            }"""
        )!!
        val chain = meta.chains[200]
        assertTrue(
            ChainPlanner.choosePlan(chain, "DEADBEEF", meta.apkSize)
                is ChainPlanner.UpdatePlan.Incremental
        )
    }
}
