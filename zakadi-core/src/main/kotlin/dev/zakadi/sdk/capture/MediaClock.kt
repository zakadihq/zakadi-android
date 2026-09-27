@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import kotlin.math.abs

/**
 * The session media clock of spec 07 section 7.5 on the camera clock: t0 is the first frame the
 * camera pipeline delivers after [restart] (or after construction), and a timestamp's `pts_ms` is
 * `floor((ts - t0) / 1e6)`, 0 for anything before t0.
 */
@InternalZakadiApi
class MediaClock {
    /** The camera timestamp of t0 in nanoseconds, or null before the first frame. */
    @Volatile
    var t0Nanos: Long? = null
        private set

    /** Takes t0 again from the next frame. */
    fun restart() {
        t0Nanos = null
    }

    /** A camera frame at [timestampNanos]: the first one after a restart becomes t0. */
    fun onVideo(timestampNanos: Long): Long {
        if (t0Nanos == null) t0Nanos = timestampNanos
        return ptsMs(timestampNanos)
    }

    /** `pts_ms` of a camera timestamp; 0 before t0 or before the first frame. */
    fun ptsMs(timestampNanos: Long): Long {
        val t0 = t0Nanos ?: return 0
        return if (timestampNanos <= t0) 0 else (timestampNanos - t0) / 1_000_000L
    }
}

/** The clock a camera's timestamps are on, which audio timestamps are mapped onto (7.5). */
@InternalZakadiApi
enum class CameraClockDomain {
    /** `SystemClock.elapsedRealtimeNanos()`, counting deep sleep. */
    BOOTTIME,

    /** `System.nanoTime()`, which stops in deep sleep. */
    MONOTONIC,

    /** Neither clock was within a second of the first frame. */
    UNKNOWN,
}

/** `SENSOR_INFO_TIMESTAMP_SOURCE_REALTIME` of `CameraCharacteristics`. */
private const val TIMESTAMP_SOURCE_REALTIME = 1

/**
 * The clock domain of spec 07 section 7.5. `SENSOR_INFO_TIMESTAMP_SOURCE` `REALTIME` means
 * BOOTTIME; for `UNKNOWN` or an unreported source, the first frame's timestamp is compared with
 * [monotonicNanos] (`System.nanoTime()`) and [boottimeNanos] (`elapsedRealtimeNanos()`) read when
 * it arrived, and the closer one wins when it is within 1 s.
 */
@InternalZakadiApi
fun cameraClockDomain(
    timestampSource: Int?,
    firstFrameNanos: Long,
    monotonicNanos: Long,
    boottimeNanos: Long,
): CameraClockDomain {
    if (timestampSource == TIMESTAMP_SOURCE_REALTIME) return CameraClockDomain.BOOTTIME
    val toMonotonic = abs(monotonicNanos - firstFrameNanos)
    val toBoottime = abs(boottimeNanos - firstFrameNanos)
    val closest = minOf(toMonotonic, toBoottime)
    return when {
        closest > 1_000_000_000L -> CameraClockDomain.UNKNOWN
        toBoottime <= toMonotonic -> CameraClockDomain.BOOTTIME
        else -> CameraClockDomain.MONOTONIC
    }
}
