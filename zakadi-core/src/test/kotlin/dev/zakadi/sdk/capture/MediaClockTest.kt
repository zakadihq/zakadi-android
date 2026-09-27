@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class MediaClockTest {
    private val s = 1_000_000_000L

    @Test
    fun t0IsTheFirstFrameAndPtsFloorsToMilliseconds() {
        val clock = MediaClock()
        assertEquals(0L, clock.onVideo(100 * s))
        assertEquals(100 * s, clock.t0Nanos)
        assertEquals(0L, clock.ptsMs(100 * s + 999_999))
        assertEquals(1L, clock.ptsMs(100 * s + 1_000_000))
        assertEquals(66L, clock.onVideo(100 * s + 66_700_000))
        assertEquals(100 * s, clock.t0Nanos)
    }

    @Test
    fun anythingBeforeT0IsZero() {
        val clock = MediaClock()
        assertEquals(0L, clock.ptsMs(7 * s))
        clock.onVideo(10 * s)
        assertEquals(0L, clock.ptsMs(9 * s))
    }

    @Test
    fun restartTakesT0FromTheNextFrame() {
        val clock = MediaClock()
        clock.onVideo(10 * s)
        clock.restart()
        assertNull(clock.t0Nanos)
        clock.onVideo(20 * s)
        assertEquals(1000L, clock.ptsMs(21 * s))
    }

    @Test
    fun aRealtimeSourceIsBoottime() {
        assertEquals(CameraClockDomain.BOOTTIME, cameraClockDomain(1, 5 * s, 900 * s, 2 * s))
    }

    @Test
    fun anUnknownSourceTakesTheClockWithinOneSecond() {
        val frame = 5_000 * s
        assertEquals(
            CameraClockDomain.BOOTTIME,
            cameraClockDomain(0, frame, 4_000 * s, frame + 30_000_000),
        )
        assertEquals(
            CameraClockDomain.MONOTONIC,
            cameraClockDomain(0, frame, frame + 20_000_000, 7_000 * s),
        )
        assertEquals(
            CameraClockDomain.MONOTONIC,
            cameraClockDomain(null, frame, frame + 20_000_000, frame + 40_000_000),
        )
        assertEquals(
            CameraClockDomain.UNKNOWN,
            cameraClockDomain(0, frame, frame + 2 * s, frame - 3 * s),
        )
    }
}
