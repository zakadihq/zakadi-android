@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Test

class CaptureStatsTest {
    private val ms = 1_000_000L

    @Test
    fun theCaptureFieldsOf77() {
        val stats = CaptureStats()
        for (i in 0 until 60) stats.onCaptured(i * 33 * ms)
        repeat(20) { stats.onDropped() }
        repeat(30) { stats.onSubmitted() }
        for (i in 0 until 28) stats.onAccessUnit((1000 + i * 30) * ms, 2_000)
        val now = stats.snapshot(1_980 * ms)
        assertEquals(30, now.capturedFps)
        assertEquals(20L, now.preEncodeDrops)
        assertEquals(2L, now.encQueue)
        assertEquals(448, now.encodedKbps)
    }

    @Test
    fun preEncodeDropsCountFromT0() {
        val stats = CaptureStats()
        repeat(5) { stats.onDropped() }
        stats.restartDrops()
        stats.onDropped()
        assertEquals(1L, stats.snapshot(0).preEncodeDrops)
    }
}
