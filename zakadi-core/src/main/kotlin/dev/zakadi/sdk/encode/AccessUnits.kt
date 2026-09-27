@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi

/** One encoder output buffer as the sender would carry it (spec 01 section 1.3.2). */
@InternalZakadiApi
class AssembledBuffer(
    /** The NAL unit types of the buffer as the encoder wrote it, in order. */
    val nalTypes: List<Int>,
    /** Whether the buffer holds an IDR slice (NAL type 5); the encoder's key flag is advisory. */
    val idr: Boolean,
    /**
     * The access unit with 4-byte start codes, SPS and PPS first when [idr]; null when the buffer
     * carries no slice (codec configuration alone), which is never sent.
     */
    val accessUnit: ByteArray?,
    /** Whether [accessUnit] carries an SPS and a PPS before its slices: the `param_sets` bit. */
    val paramSets: Boolean,
    /** Whether the SPS or the PPS changed with this buffer. */
    val paramSetsChanged: Boolean,
)

/**
 * Turns encoder output buffers into access units (spec 07 section 7.19): it learns the SPS and PPS
 * from every buffer, never lets a buffer without a slice go out, takes IDRs from the NAL type, puts
 * the latest SPS and PPS in front of an IDR that lacks them, and normalises every start code to 4
 * bytes.
 */
@InternalZakadiApi
class AccessUnitAssembler(val parameterSets: ParameterSets = ParameterSets()) {

    /** Learns the parameter sets of `csd-0` and `csd-1`; true when the SPS or the PPS changed. */
    fun learnConfig(vararg csd: ByteArray?): Boolean =
        parameterSets.learn(csd.filterNotNull().flatMap { AnnexB.split(it) })

    /** Assembles one output buffer, [stream] being its bytes from offset to offset plus size. */
    fun assemble(stream: ByteArray): AssembledBuffer {
        val nals = AnnexB.split(stream)
        val changed = parameterSets.learn(nals)
        val types = nals.map(AnnexB::type)
        if (types.none(AnnexB::isVcl)) {
            return AssembledBuffer(types, idr = false, null, paramSets = false, changed)
        }
        val idr = AnnexB.NAL_IDR in types
        val out = if (idr) withParameterSets(nals, types) else nals
        return AssembledBuffer(types, idr, AnnexB.join4(out), carriesParameterSets(out), changed)
    }

    private fun withParameterSets(nals: List<ByteArray>, types: List<Int>): List<ByteArray> {
        if (AnnexB.NAL_SPS in types && AnnexB.NAL_PPS in types) return nals
        val both = parameterSets.both ?: return nals
        val leading = types.takeWhile { it == AnnexB.NAL_AUD }.size
        val rest =
            nals.drop(leading).filter {
                val type = AnnexB.type(it)
                type != AnnexB.NAL_SPS && type != AnnexB.NAL_PPS
            }
        return nals.take(leading) + both + rest
    }

    private fun carriesParameterSets(nals: List<ByteArray>): Boolean {
        val types = nals.map(AnnexB::type)
        val firstSlice = types.indexOfFirst(AnnexB::isVcl)
        val sps = types.indexOf(AnnexB.NAL_SPS)
        val pps = types.indexOf(AnnexB.NAL_PPS)
        return sps in 0 until firstSlice && pps in 0 until firstSlice
    }
}
