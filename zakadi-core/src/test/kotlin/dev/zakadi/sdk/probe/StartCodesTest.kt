@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.AUD
import dev.zakadi.sdk.encode.AccessUnitAssembler
import dev.zakadi.sdk.encode.IDR
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.PPS
import dev.zakadi.sdk.encode.SLICE
import dev.zakadi.sdk.encode.SPS
import dev.zakadi.sdk.encode.bytes
import dev.zakadi.sdk.encode.stream3
import dev.zakadi.sdk.encode.stream4
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * `sc3` of an `out` line: the 3-byte start codes of a buffer as the encoder wrote it, told from
 * what the listener reports once the assembler of spec 07 section 7.19 has normalised it.
 */
class StartCodesTest {
    private val assembler = AccessUnitAssembler()

    private fun sc3(stream: ByteArray): Int? {
        val assembled = assembler.assemble(stream)
        val buffer =
            OutputBuffer(
                atNanos = 0,
                encoderId = 1,
                presentationTimeUs = 0,
                ptsMs = 0,
                size = stream.size,
                flags = 0,
                keyFlag = assembled.idr,
                codecConfig = false,
                endOfStream = false,
                nalTypes = assembled.nalTypes,
                idr = assembled.idr,
                accessUnit = assembled.accessUnit,
                paramSets = assembled.paramSets,
            )
        val sets = assembler.parameterSets
        return threeByteStartCodes(buffer, sets.sps, sets.pps)
    }

    @Test
    fun startCodesAreCountedAsTheEncoderWroteThem() {
        assertEquals(0, sc3(stream4(SLICE)))
        assertEquals(1, sc3(stream3(SLICE)))
        assertEquals(2, sc3(stream3(SPS) + stream4(PPS) + stream3(IDR)))
        assertEquals(0, sc3(stream4(SPS, PPS, IDR)))
        assertEquals(3, sc3(stream3(AUD, SLICE, SLICE)))
    }

    @Test
    fun parameterSetsPutInFrontOfAnIdrAreNotCounted() {
        assembler.learnConfig(stream4(SPS), stream4(PPS))
        assertEquals(1, sc3(stream3(IDR)))
        assertEquals(0, sc3(stream4(IDR)))
        assertEquals(1, sc3(stream4(AUD) + stream3(IDR)))
        val newSps = bytes(0x67, 0x42, 0xE0, 0x1E, 0xDA)
        assertEquals(2, sc3(stream3(newSps, IDR)))
        assertEquals(1, sc3(stream4(PPS) + stream3(IDR)))
    }

    @Test
    fun bytesTheCountsCannotPlaceGiveNull() {
        assertNull("trailing zeros", sc3(stream4(SLICE) + bytes(0, 0)))
        assertNull("no start code", sc3(SLICE))
        assertNull("configuration only", sc3(stream3(SPS, PPS)))
        assembler.learnConfig(stream4(SPS), stream4(PPS))
        assertNull("two SPS", sc3(stream3(SPS, SPS, IDR)))
    }
}
