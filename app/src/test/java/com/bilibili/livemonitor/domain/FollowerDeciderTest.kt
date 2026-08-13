package com.bilibili.livemonitor.domain

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class FollowerDeciderTest {

    @Test
    fun `首次必采 间隔内不采 过闸才采`() {
        val now = 1_000_000_000_000L
        assertTrue(FollowerDecider.shouldSnapshot(null, now))
        assertFalse(FollowerDecider.shouldSnapshot(now - 3_600_000L, now))
        assertFalse(
            FollowerDecider.shouldSnapshot(
                now - FollowerDecider.SNAPSHOT_MIN_INTERVAL_MS + 1000, now
            )
        )
        assertTrue(
            FollowerDecider.shouldSnapshot(
                now - FollowerDecider.SNAPSHOT_MIN_INTERVAL_MS, now
            )
        )
    }
}
