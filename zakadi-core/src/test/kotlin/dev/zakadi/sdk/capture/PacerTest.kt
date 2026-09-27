@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class PacerTest {
    private val ms = 1_000_000L
    private val start = 5_000_000_000_000L

    /** Camera frames at [cameraFps] for [seconds], each shifted by up to [jitterMs] either way. */
    private fun frames(cameraFps: Double, seconds: Double, jitterMs: Long = 0, from: Long = start) =
        (0 until (cameraFps * seconds).toInt()).map { i ->
            val jitter = if (i % 2 == 0) jitterMs * ms else -jitterMs * ms
            from + (i * 1e9 / cameraFps).toLong() + jitter
        }

    private fun decide(pacer: Pacer, timestamps: List<Long>) = timestamps.map(pacer::decide)

    @Test
    fun keepsTheRungRateFromAFasterCamera() {
        for (fps in listOf(10, 12, 15, 20)) {
            val kept = decide(Pacer(fps), frames(30.0, 10.0, jitterMs = 2))
            val count = kept.count { it == PaceDecision.KEEP }
            assertTrue("$fps fps kept $count in 10 s", count in fps * 10 - 1..fps * 10 + 1)
            assertTrue(kept.all { it != PaceDecision.DROP_DECIMATION })
        }
    }

    @Test
    fun keepsEveryFrameOfASlowerCamera() {
        val kept = decide(Pacer(15), frames(12.0, 5.0, jitterMs = 2))
        assertTrue(kept.all { it == PaceDecision.KEEP })
    }

    @Test
    fun aFrameUpTo3MsBeforeItsSlotIsKept() {
        val early = Pacer(10)
        assertEquals(PaceDecision.KEEP, early.decide(start))
        assertEquals(PaceDecision.KEEP, early.decide(start + 97 * ms))
        val tooEarly = Pacer(10)
        tooEarly.decide(start)
        assertEquals(PaceDecision.DROP_PACING, tooEarly.decide(start + 96 * ms))
    }

    @Test
    fun aGapDoesNotLeadToABurst() {
        val pacer = Pacer(15)
        decide(pacer, frames(30.0, 1.0))
        val after = decide(pacer, frames(30.0, 2.0, from = start + 3_000 * ms))
        val count = after.count { it == PaceDecision.KEEP }
        assertTrue("kept $count in 2 s after a gap", count in 29..31)
    }

    @Test
    fun decimationDropsEvery4thOr2ndKeptFrame() {
        val one = decide(Pacer(20, decimation = 1), frames(30.0, 4.0))
        val paced = one.count { it != PaceDecision.DROP_PACING }
        assertEquals(paced / 4, one.count { it == PaceDecision.DROP_DECIMATION })
        val keptIndices = one.withIndex().filter { it.value != PaceDecision.DROP_PACING }
        assertEquals(PaceDecision.DROP_DECIMATION, keptIndices[3].value)
        assertEquals(PaceDecision.KEEP, keptIndices[4].value)
        val two = decide(Pacer(20, decimation = 2), frames(30.0, 4.0))
        assertEquals(
            two.count { it != PaceDecision.DROP_PACING } / 2,
            two.count { it == PaceDecision.DROP_DECIMATION },
        )
    }

    @Test
    fun maxFpsCapsTheRungRate() {
        val ladder = listOf(Rung(0, 480, 640, 20, 900))
        assertEquals(12, CaptureConfig(ladder, 0, maxFps = 12).pacedFps(ladder[0]))
        assertEquals(20, CaptureConfig(ladder, 0, maxFps = 25).pacedFps(ladder[0]))
        assertEquals(20, CaptureConfig(ladder, 0).pacedFps(ladder[0]))
        val kept = decide(Pacer(12), frames(30.0, 10.0)).count { it == PaceDecision.KEEP }
        assertTrue("kept $kept", kept in 119..121)
    }

    @Test
    fun configureRestartsTheSlots() {
        val pacer = Pacer(10)
        pacer.decide(start)
        pacer.configure(20)
        assertEquals(PaceDecision.KEEP, pacer.decide(start + 1 * ms))
        assertEquals(PaceDecision.DROP_PACING, pacer.decide(start + 30 * ms))
        assertEquals(PaceDecision.KEEP, pacer.decide(start + 50 * ms))
    }
}
