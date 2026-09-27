@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class CaptureConfigTest {
    /** The ladder of `ready` in spec 01 section 1.5. */
    private val ladder =
        listOf(
            Rung(0, 480, 640, 20, 900),
            Rung(1, 480, 640, 20, 600),
            Rung(2, 480, 640, 15, 400),
            Rung(3, 336, 448, 12, 250),
            Rung(4, 288, 384, 10, 150),
        )

    @Test
    fun anUnsupportedSizeCapsTheBestRung() {
        assertEquals(0, bestSupportedRung(ladder, 0) { true })
        assertEquals(3, bestSupportedRung(ladder, 0) { it.width <= 336 })
        assertEquals(4, bestSupportedRung(ladder, 4) { true })
        assertEquals(2, bestSupportedRung(ladder, 2) { true })
        assertNull(bestSupportedRung(ladder, 0) { false })
    }

    @Test
    fun aRungAboveTheCapIsAppliedAtTheCap() {
        assertEquals(2, appliedRung(ladder, 0, best = 2))
        assertEquals(3, appliedRung(ladder, 3, best = 2))
        assertEquals(4, appliedRung(ladder, 9, best = 0))
    }

    @Test
    fun theAeRangeIsTheOneNearestTheCap() {
        val ranges = listOf(15..15, 7..30, 30..30, 15..30)
        assertEquals(15..30, aeTargetFpsRange(ranges, 30))
        assertEquals(15..15, aeTargetFpsRange(ranges, 15))
        assertEquals(15..15, aeTargetFpsRange(ranges, 12))
        assertNull(aeTargetFpsRange(emptyList(), 30))
    }

    @Test
    fun theConfigChecksItsLadder() {
        assertThrows(IllegalArgumentException::class.java) { CaptureConfig(emptyList(), 0) }
        assertThrows(IllegalArgumentException::class.java) { CaptureConfig(ladder, 5) }
        assertThrows(IllegalArgumentException::class.java) {
            CaptureConfig(ladder + Rung(0, 480, 640, 20, 900), 0)
        }
        assertEquals(ladder[3], CaptureConfig(ladder, 3).rung(3))
    }
}
