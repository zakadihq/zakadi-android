package dev.zakadi.sdk

/**
 * The 8-byte header of a binary media message (spec 01 section 1.3.1): multi-byte fields are
 * little-endian and bit 7 is the most significant bit of a byte.
 */
@InternalZakadiApi
object Header {
    const val SIZE: Int = 8
    const val VERSION: Int = 0

    const val TYPE_VIDEO: Int = 0
    const val TYPE_AUDIO: Int = 1
    const val TYPE_PROBE: Int = 2
    const val TYPE_AUDIO_BATCH: Int = 3

    /** Encodes a header; [seq] wraps as a uint16, [ptsMs] is a uint32. */
    fun encode(
        type: Int,
        key: Boolean,
        paramSets: Boolean,
        rungChanged: Boolean,
        rung: Int,
        seq: Int,
        ptsMs: Long,
    ): ByteArray {
        require(type in TYPE_VIDEO..TYPE_AUDIO_BATCH) { "type $type is not 0..3" }
        require(rung in 0..15) { "rung $rung is not 0..15" }
        require(ptsMs in 0..0xFFFF_FFFFL) { "pts_ms $ptsMs is not a uint32" }
        return ByteArray(SIZE).also { b ->
            b[0] =
                ((type shl 4) or
                        (if (key) 8 else 0) or
                        (if (paramSets) 4 else 0) or
                        (if (rungChanged) 2 else 0))
                    .toByte()
            b[1] = (rung shl 4).toByte()
            b[2] = seq.toByte()
            b[3] = (seq ushr 8).toByte()
            for (i in 0..3) b[4 + i] = (ptsMs ushr (8 * i)).toByte()
        }
    }

    /**
     * Decodes the header at the start of [message].
     *
     * @throws FramingException with [FramingError.SHORT_HEADER],
     *   [FramingError.UNSUPPORTED_VERSION], [FramingError.RESERVED_BIT_SET] or
     *   [FramingError.RESERVED_BITS_SET].
     */
    fun decode(message: ByteArray): MediaHeader {
        if (message.size < SIZE) {
            throw FramingException(FramingError.SHORT_HEADER, "${message.size} bytes, need $SIZE")
        }
        val b0 = message[0].toInt() and 0xFF
        val b1 = message[1].toInt() and 0xFF
        val version = b0 ushr 6
        if (version != VERSION) {
            throw FramingException(FramingError.UNSUPPORTED_VERSION, "framing version $version")
        }
        if ((b0 and 0x01) != 0) {
            throw FramingException(FramingError.RESERVED_BIT_SET, "byte 0 bit 0 must be 0")
        }
        if ((b1 and 0x0F) != 0) {
            throw FramingException(FramingError.RESERVED_BITS_SET, "byte 1 bits 3-0 must be 0")
        }
        return MediaHeader(
            type = (b0 ushr 4) and 0x03,
            keyframe = (b0 and 0x08) != 0,
            paramSets = (b0 and 0x04) != 0,
            rungChanged = (b0 and 0x02) != 0,
            rung = b1 ushr 4,
            seq = uint16(message, 2),
            ptsMs = uint32(message, 4),
        )
    }
}

/** The fields of a decoded [Header]; the framing version is always [Header.VERSION]. */
@InternalZakadiApi
data class MediaHeader(
    val type: Int,
    val keyframe: Boolean,
    val paramSets: Boolean,
    val rungChanged: Boolean,
    val rung: Int,
    val seq: Int,
    val ptsMs: Long,
)

/** The error codes of the framing vectors (spec 01 section 1.12). */
@InternalZakadiApi
enum class FramingError(val code: String) {
    SHORT_HEADER("short_header"),
    UNSUPPORTED_VERSION("unsupported_version"),
    RESERVED_BIT_SET("reserved_bit_set"),
    RESERVED_BITS_SET("reserved_bits_set"),
    SHORT_PROBE("short_probe"),
    TRUNCATED_BATCH_RECORD("truncated_batch_record"),
    BATCH_OUT_OF_ORDER("batch_out_of_order"),
    EMPTY_BATCH("empty_batch"),
}

/** A media message that breaks the framing rules of spec 01 section 1.3. */
@InternalZakadiApi
class FramingException(val error: FramingError, detail: String) :
    IllegalArgumentException("${error.code}: $detail")

internal fun uint16(bytes: ByteArray, at: Int): Int =
    (bytes[at].toInt() and 0xFF) or ((bytes[at + 1].toInt() and 0xFF) shl 8)

internal fun uint32(bytes: ByteArray, at: Int): Long =
    (0..3).fold(0L) { acc, i -> acc or ((bytes[at + i].toLong() and 0xFF) shl (8 * i)) }
