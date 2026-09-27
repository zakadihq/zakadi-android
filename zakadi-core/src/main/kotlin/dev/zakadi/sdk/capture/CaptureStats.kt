@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi

/** The capture fields of the `stats` message (spec 07 section 7.7) at one moment. */
@InternalZakadiApi
data class CaptureStatsSnapshot(
    /** `captured_fps`: camera frames in the last 1000 ms, before pacing. */
    val capturedFps: Int,
    /** `pre_encode_drops`: frames dropped before the encoder since t0. */
    val preEncodeDrops: Long,
    /** `enc_queue`: frames submitted minus access units returned. */
    val encQueue: Long,
    /** `encoded_kbps`: access unit bits in the last 1000 ms, in kbit/s. */
    val encodedKbps: Int,
)

/**
 * Counts what the capture pipeline reports into the capture fields of spec 07 section 7.7. Times
 * are `elapsedRealtimeNanos()` values; every method is thread-safe.
 */
@InternalZakadiApi
class CaptureStats(private val windowNanos: Long = 1_000_000_000L) {
    private val captured = ArrayDeque<Long>()
    private val encodedAt = ArrayDeque<Long>()
    private val encodedBytes = ArrayDeque<Int>()
    private var drops = 0L
    private var submitted = 0L
    private var returned = 0L

    /** A camera frame arrived at [atNanos]. */
    @Synchronized
    fun onCaptured(atNanos: Long) {
        captured.addLast(atNanos)
        trim(atNanos)
    }

    /** A frame was dropped before the encoder. */
    @Synchronized
    fun onDropped() {
        drops++
    }

    /** A frame went to the encoder. */
    @Synchronized
    fun onSubmitted() {
        submitted++
    }

    /** An access unit of [bytes] left the encoder at [atNanos]. */
    @Synchronized
    fun onAccessUnit(atNanos: Long, bytes: Int) {
        returned++
        encodedAt.addLast(atNanos)
        encodedBytes.addLast(bytes)
        trim(atNanos)
    }

    /** Restarts `pre_encode_drops`, which counts from t0. */
    @Synchronized
    fun restartDrops() {
        drops = 0
    }

    /** The fields at [nowNanos]. */
    @Synchronized
    fun snapshot(nowNanos: Long): CaptureStatsSnapshot {
        trim(nowNanos)
        val bits = encodedBytes.sumOf { it.toLong() } * 8
        return CaptureStatsSnapshot(
            capturedFps = captured.size,
            preEncodeDrops = drops,
            encQueue = submitted - returned,
            encodedKbps = (bits * 1_000_000_000L / windowNanos / 1000).toInt(),
        )
    }

    private fun trim(nowNanos: Long) {
        while (captured.isNotEmpty() && nowNanos - captured.first() >= windowNanos) {
            captured.removeFirst()
        }
        while (encodedAt.isNotEmpty() && nowNanos - encodedAt.first() >= windowNanos) {
            encodedAt.removeFirst()
            encodedBytes.removeFirst()
        }
    }
}
