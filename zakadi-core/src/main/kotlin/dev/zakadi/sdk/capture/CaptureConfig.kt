@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import androidx.camera.core.MeteringPointFactory
import androidx.camera.core.Preview
import dev.zakadi.sdk.InternalZakadiApi

/** How a [CapturePipeline] starts. */
@InternalZakadiApi
class CaptureConfig(
    /** `ready.ladder` (spec 01 section 1.5). */
    val ladder: List<Rung>,
    /** The rung to start at. */
    val startRung: Int,
    val encoder: EncoderPreference = EncoderPreference.HARDWARE,
    val path: CapturePath = CapturePath.A,
    /** The best rung allowed: 2 on tier L, or `device_quirks.max_rung` (spec 07 section 7.26). */
    val bestRung: Int = 0,
    /** The frame rate cap: 15 on tier L, or `device_quirks.max_fps`; null for none. */
    val maxFps: Int? = null,
    /** `ready.gop_ms`. */
    val gopMs: Int = 2000,
    /** A `PreviewView`'s surface provider, which mirrors the front camera; null when headless. */
    val previewSurfaceProvider: Preview.SurfaceProvider? = null,
    /**
     * The factory of the auto-exposure metering point: a `PreviewView`'s, or null when headless for
     * a `SurfaceOrientedMeteringPointFactory` of 1 x 1.
     */
    val meteringPointFactory: MeteringPointFactory? = null,
    /** The oval centre in the coordinates of [meteringPointFactory]. */
    val meteringX: Float = 0.5f,
    val meteringY: Float = 0.5f,
) {
    init {
        require(ladder.isNotEmpty()) { "the ladder is empty" }
        require(ladder.map { it.index }.toSet().size == ladder.size) { "rung indices repeat" }
        require(ladder.any { it.index == startRung }) { "rung $startRung is not in the ladder" }
        require(maxFps == null || maxFps > 0) { "max_fps $maxFps is not positive" }
    }

    /** The rung of [index]: the ladder entry with that index. */
    fun rung(index: Int): Rung = ladder.first { it.index == index }

    /** The rate the pacer keeps for [rung]: its own, capped by [maxFps]. */
    fun pacedFps(rung: Rung): Int = maxFps?.let { minOf(it, rung.fps) } ?: rung.fps
}

/**
 * The best rung [ladder] allows from [cap] on (spec 07 sections 7.19 and 7.26): the best one whose
 * size [supported] accepts, as `isSizeSupported` caps it. Null when none is supported.
 */
@InternalZakadiApi
fun bestSupportedRung(ladder: List<Rung>, cap: Int, supported: (Rung) -> Boolean): Int? =
    ladder.filter { it.index >= cap }.sortedBy { it.index }.firstOrNull(supported)?.index

/**
 * The rung a request for [requested] gets (spec 07 section 7.26): [requested], or [best] when it
 * asks for a better one, or the worst rung of [ladder] when it asks for one past it.
 */
@InternalZakadiApi
fun appliedRung(ladder: List<Rung>, requested: Int, best: Int): Int {
    val worst = ladder.maxOf { it.index }
    val wanted = maxOf(requested, best).coerceAtMost(worst)
    return ladder.map { it.index }.filter { it >= wanted }.minOrNull() ?: worst
}

/**
 * The auto-exposure target frame rate range of path B (spec 07 section 7.18) among the camera's
 * [supported] ranges, for a cap of [maxFps]: the range whose upper bound is closest to the cap,
 * then whose lower bound is closest to 15 (or the cap when lower). Null when none is listed.
 */
@InternalZakadiApi
fun aeTargetFpsRange(supported: List<IntRange>, maxFps: Int): IntRange? {
    val lower = minOf(15, maxFps)
    return supported.minWithOrNull(
        compareBy<IntRange>(
            { kotlin.math.abs(it.last - maxFps) },
            { kotlin.math.abs(it.first - lower) },
        )
    )
}
