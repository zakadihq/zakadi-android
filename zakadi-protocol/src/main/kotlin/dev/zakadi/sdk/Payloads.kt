package dev.zakadi.sdk

/**
 * Reads a probe payload (type 2, spec 01 section 1.3.2): the client monotonic send time in
 * microseconds, a uint64 little-endian in bytes 0-7, followed by filler.
 *
 * @throws FramingException with [FramingError.SHORT_PROBE] when the payload has fewer than 8 bytes.
 */
@InternalZakadiApi
fun parseProbePayload(payload: ByteArray): Long {
    if (payload.size < 8) {
        throw FramingException(FramingError.SHORT_PROBE, "${payload.size} bytes, need 8")
    }
    return (0..7).fold(0L) { acc, i -> acc or ((payload[i].toLong() and 0xFF) shl (8 * i)) }
}

/** One packet of an audio batch, [ptsDeltaMs] after the header's `pts_ms`. */
@InternalZakadiApi class AudioBatchRecord(val ptsDeltaMs: Int, val packet: ByteArray)

/**
 * Reads an audio batch payload (type 3, spec 01 section 1.3.2): records of a uint16 length, a
 * uint16 `pts_delta_ms` and the packet, ordered by `pts_delta_ms`.
 *
 * @throws FramingException with [FramingError.TRUNCATED_BATCH_RECORD],
 *   [FramingError.BATCH_OUT_OF_ORDER] or [FramingError.EMPTY_BATCH].
 */
@InternalZakadiApi
fun parseAudioBatch(payload: ByteArray): List<AudioBatchRecord> {
    val records = ArrayList<AudioBatchRecord>()
    var at = 0
    var last = -1
    while (at < payload.size) {
        if (payload.size - at < 4) {
            throw FramingException(FramingError.TRUNCATED_BATCH_RECORD, "record header at $at")
        }
        val length = uint16(payload, at)
        val delta = uint16(payload, at + 2)
        at += 4
        if (payload.size - at < length) {
            throw FramingException(
                FramingError.TRUNCATED_BATCH_RECORD,
                "record declares $length bytes, ${payload.size - at} remain",
            )
        }
        if (delta < last) {
            throw FramingException(
                FramingError.BATCH_OUT_OF_ORDER,
                "pts_delta_ms $delta after $last",
            )
        }
        records += AudioBatchRecord(delta, payload.copyOfRange(at, at + length))
        at += length
        last = delta
    }
    if (records.isEmpty()) {
        throw FramingException(FramingError.EMPTY_BATCH, "an audio batch carries no record")
    }
    return records
}
