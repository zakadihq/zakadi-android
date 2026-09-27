@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi

/** What the pacer does with a camera frame (spec 07 section 7.18). */
@InternalZakadiApi
enum class PaceDecision {
    /** The frame goes to the encoder. */
    KEEP,

    /** Dropped: it came before its slot at the paced rate. */
    DROP_PACING,

    /** Dropped by decimation: every 4th kept frame at level 1, every 2nd at level 2. */
    DROP_DECIMATION,
}

/**
 * The frame pacer of spec 07 section 7.18. Slots run at [fps] from the first frame after each
 * [configure]; a frame is kept when it arrives no earlier than 3 ms before the next slot, and the
 * next slot is then the first one more than 3 ms after it. Of the kept frames, decimation level 1
 * drops every 4th and level 2 every 2nd. Every drop is a pre-encode drop (7.7).
 */
@InternalZakadiApi
class Pacer(fps: Int, decimation: Int = 0) {
    /** The paced rate: the rung rate, already capped by `device_quirks.max_fps`. */
    var fps: Int = fps
        private set

    /** The decimation level, 0, 1 or 2. */
    var decimation: Int = decimation
        private set

    private var anchor = NO_ANCHOR
    private var nextSlot = 0L
    private var kept = 0L

    init {
        configure(fps, decimation)
    }

    /** Sets the rate and the decimation level; slots restart from the next frame. */
    fun configure(fps: Int, decimation: Int = this.decimation) {
        require(fps > 0) { "fps $fps is not positive" }
        require(decimation in 0..2) { "decimation $decimation is not 0, 1 or 2" }
        this.fps = fps
        this.decimation = decimation
        anchor = NO_ANCHOR
        kept = 0
    }

    /** Decides for a camera frame at [timestampNanos]; timestamps must not go backwards. */
    fun decide(timestampNanos: Long): PaceDecision {
        if (anchor == NO_ANCHOR) {
            anchor = timestampNanos
            nextSlot = 0
        }
        if (timestampNanos < slotTime(nextSlot) - TOLERANCE_NANOS) return PaceDecision.DROP_PACING
        val sinceAnchor = timestampNanos + TOLERANCE_NANOS - anchor
        nextSlot = maxOf(nextSlot + 1, sinceAnchor * fps / NANOS_PER_SECOND + 1)
        kept++
        val every =
            when (decimation) {
                1 -> 4L
                2 -> 2L
                else -> 0L
            }
        return if (every > 0 && kept % every == 0L) PaceDecision.DROP_DECIMATION
        else PaceDecision.KEEP
    }

    private fun slotTime(slot: Long): Long = anchor + slot * NANOS_PER_SECOND / fps

    private companion object {
        const val NANOS_PER_SECOND = 1_000_000_000L
        const val TOLERANCE_NANOS = 3_000_000L
        const val NO_ANCHOR = Long.MIN_VALUE
    }
}
