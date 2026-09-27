@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi

/**
 * The latest SPS and PPS of one encoder (spec 07 section 7.19), learnt from any output buffer or
 * from the `csd-0` and `csd-1` of its output format, and the `config.video.codec` string of the
 * first SPS (spec 01 section 1.4).
 */
@InternalZakadiApi
class ParameterSets {
    /** The latest SPS NAL unit, without its start code. */
    var sps: ByteArray? = null
        private set

    /** The latest PPS NAL unit, without its start code. */
    var pps: ByteArray? = null
        private set

    /** `avc1.PPCCLL` from the first SPS this encoder produced, or null before it. */
    var codecString: String? = null
        private set

    /** Both parameter sets, SPS first, once both are known. */
    val both: List<ByteArray>?
        get() {
            val s = sps ?: return null
            val p = pps ?: return null
            return listOf(s, p)
        }

    /** Learns the SPS and PPS among [nals]; true when either differs from the one known before. */
    fun learn(nals: List<ByteArray>): Boolean {
        var changed = false
        for (nal in nals) {
            when (AnnexB.type(nal)) {
                AnnexB.NAL_SPS ->
                    if (!nal.contentEquals(sps)) {
                        sps = nal
                        if (codecString == null) codecString = avcCodecString(nal)
                        changed = true
                    }
                AnnexB.NAL_PPS ->
                    if (!nal.contentEquals(pps)) {
                        pps = nal
                        changed = true
                    }
            }
        }
        return changed
    }
}

/**
 * The RFC 6381 codec string of an SPS NAL unit (without its start code): `avc1.` then
 * `profile_idc`, the constraint flags byte and `level_idc` as two upper-case hex digits each, as in
 * `avc1.42E01F`. Null when [sps] is not an SPS or is too short.
 */
@InternalZakadiApi
fun avcCodecString(sps: ByteArray): String? {
    if (sps.isEmpty() || AnnexB.type(sps) != AnnexB.NAL_SPS) return null
    val rbsp = unescape(sps, 1, 3)
    if (rbsp.size < 3) return null
    return "avc1." + rbsp.joinToString("") { hexByte(it.toInt() and 0xFF) }
}

private const val HEX_DIGITS = "0123456789ABCDEF"

private fun hexByte(b: Int): String = "${HEX_DIGITS[b ushr 4]}${HEX_DIGITS[b and 0x0F]}"

/** The first [count] RBSP bytes of [nal] from [from], without emulation prevention bytes. */
private fun unescape(nal: ByteArray, from: Int, count: Int): ByteArray {
    val out = ArrayList<Byte>(count)
    var zeros = 0
    var i = from
    while (i < nal.size && out.size < count) {
        val b = nal[i]
        if (zeros >= 2 && b.toInt() == 3) {
            zeros = 0
        } else {
            out += b
            zeros = if (b.toInt() == 0) zeros + 1 else 0
        }
        i++
    }
    return out.toByteArray()
}
