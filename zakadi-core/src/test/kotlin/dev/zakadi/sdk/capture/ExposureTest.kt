@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Test

class ExposureTest {
    private fun index(step: Double, lower: Int = -12, upper: Int = 12) =
        exposureCompensationIndex(EXPOSURE_COMPENSATION_EV, step, lower, upper)

    @Test
    fun plusPointThreeEvInTheDevicesSteps() {
        assertEquals(1, index(1.0 / 3))
        assertEquals(1, index(0.5))
        assertEquals(2, index(1.0 / 6))
        assertEquals(3, index(0.1))
        assertEquals(1, index(0.6))
        assertEquals(0, index(1.0))
    }

    @Test
    fun clampedToTheDevicesRange() {
        assertEquals(0, index(0.1, lower = -2, upper = 0))
        assertEquals(4, index(0.1, lower = 4, upper = 6))
    }
}
