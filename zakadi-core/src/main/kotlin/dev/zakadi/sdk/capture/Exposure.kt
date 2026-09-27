@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import kotlin.math.roundToInt

/** The exposure compensation of spec 05 section 5.3, in EV. */
@InternalZakadiApi const val EXPOSURE_COMPENSATION_EV: Double = 0.3

/**
 * The exposure compensation index of spec 07 section 7.18 for [ev] (+0.3 EV): `round(ev / step)`,
 * halves away from zero, clamped to the device's range [lower] to [upper].
 */
@InternalZakadiApi
fun exposureCompensationIndex(ev: Double, step: Double, lower: Int, upper: Int): Int {
    require(step > 0) { "exposure step $step is not positive" }
    return (ev / step).roundToInt().coerceIn(lower, upper)
}
