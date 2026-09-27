@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi

/** A keyframe request answered by an IDR: the `ms_to_idr` of spec 07 section 7.19. */
@InternalZakadiApi
class KeyframeAnswer(
    /** Milliseconds from the request to the IDR leaving the encoder. */
    val msToIdr: Long,
    /** Frames submitted to the encoder between the request and the IDR. */
    val framesToIdr: Int,
    /** Whether the request had been repeated. */
    val repeated: Boolean,
)

/**
 * The keyframe requests of spec 07 section 7.19. Each request asks the encoder for a sync frame;
 * with no IDR [repeatAfterNanos] later (500 ms) it is repeated once; the first IDR answers it. A
 * request made while another is pending replaces it.
 */
@InternalZakadiApi
class KeyframeRequests(private val repeatAfterNanos: Long = 500_000_000L) {
    private var requestedAt = NONE
    private var repeated = false
    private var frames = 0

    /** Whether a request waits for its IDR. */
    val pending: Boolean
        get() = requestedAt != NONE

    /** Records a request made at [nowNanos]; the caller asks the encoder for a sync frame. */
    fun request(nowNanos: Long) {
        requestedAt = nowNanos
        repeated = false
        frames = 0
    }

    /** Counts a frame submitted to the encoder while a request is pending. */
    fun onFrameSubmitted() {
        if (pending) frames++
    }

    /**
     * True, once per request, when the request is still unanswered [repeatAfterNanos] after it was
     * made: the caller asks the encoder for a sync frame again.
     */
    fun repeatDue(nowNanos: Long): Boolean {
        if (!pending || repeated || nowNanos - requestedAt < repeatAfterNanos) return false
        repeated = true
        return true
    }

    /** An IDR left the encoder at [nowNanos]: the request it answers, or null when none waited. */
    fun onIdr(nowNanos: Long): KeyframeAnswer? {
        if (!pending) return null
        val answer = KeyframeAnswer((nowNanos - requestedAt) / 1_000_000L, frames, repeated)
        requestedAt = NONE
        return answer
    }

    private companion object {
        const val NONE = Long.MIN_VALUE
    }
}
