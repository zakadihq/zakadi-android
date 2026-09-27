@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer

/**
 * The H.264 Annex-B byte stream of spec 01 section 1.3.2: NAL units split at 3- or 4-byte start
 * codes, as encoders write them, and joined with the 4-byte start code `00 00 00 01` the protocol
 * requires.
 */
@InternalZakadiApi
object AnnexB {
    const val NAL_SLICE: Int = 1
    const val NAL_IDR: Int = 5
    const val NAL_SEI: Int = 6
    const val NAL_SPS: Int = 7
    const val NAL_PPS: Int = 8
    const val NAL_AUD: Int = 9

    private val START_CODE = byteArrayOf(0, 0, 0, 1)

    /** The `nal_unit_type` of [nal], a NAL unit without its start code. */
    fun type(nal: ByteArray): Int = nal[0].toInt() and 0x1F

    /** Whether [type] is a coded slice (types 1 to 5), the part of an access unit that is video. */
    fun isVcl(type: Int): Boolean = type in NAL_SLICE..NAL_IDR

    /** Splits [size] bytes of [buffer] from [offset]; the buffer's position is left as it was. */
    fun split(buffer: ByteBuffer, offset: Int, size: Int): List<ByteArray> {
        val bytes = ByteArray(size)
        buffer.duplicate().apply { position(offset) }.get(bytes)
        return split(bytes)
    }

    /**
     * The NAL units of [stream] without their start codes. Zero bytes before a start code belong to
     * it (a 4-byte start code or trailing zeros), bytes before the first start code are dropped,
     * and a stream with no start code at all is one NAL unit.
     */
    fun split(stream: ByteArray): List<ByteArray> {
        val starts = ArrayList<Int>()
        var i = 0
        while (i + 2 < stream.size) {
            if (
                stream[i].toInt() == 0 && stream[i + 1].toInt() == 0 && stream[i + 2].toInt() == 1
            ) {
                starts += i + 3
                i += 3
            } else {
                i++
            }
        }
        if (starts.isEmpty()) return if (stream.isEmpty()) emptyList() else listOf(stream.copyOf())
        val nals = ArrayList<ByteArray>(starts.size)
        for ((n, start) in starts.withIndex()) {
            var end = if (n + 1 < starts.size) starts[n + 1] - 3 else stream.size
            while (end > start && stream[end - 1].toInt() == 0) end--
            if (end > start) nals += stream.copyOfRange(start, end)
        }
        return nals
    }

    /** [nals] as one Annex-B stream with a 4-byte start code before each. */
    fun join4(nals: List<ByteArray>): ByteArray {
        val out = ByteArray(nals.sumOf { it.size + START_CODE.size })
        var at = 0
        for (nal in nals) {
            START_CODE.copyInto(out, at)
            nal.copyInto(out, at + START_CODE.size)
            at += START_CODE.size + nal.size
        }
        return out
    }
}
