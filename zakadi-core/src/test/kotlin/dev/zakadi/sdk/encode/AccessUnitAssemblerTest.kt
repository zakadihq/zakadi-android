@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class AccessUnitAssemblerTest {
    private fun types(au: ByteArray?) = AnnexB.split(checkNotNull(au)).map(AnnexB::type)

    @Test
    fun aConfigBufferIsLearntAndNeverSentAlone() {
        val assembler = AccessUnitAssembler()
        val config = assembler.assemble(stream4(SPS, PPS))
        assertNull(config.accessUnit)
        assertEquals(listOf(7, 8), config.nalTypes)
        assertTrue(config.paramSetsChanged)
        assertFalse(config.idr)
        assertEquals("avc1.42E01F", assembler.parameterSets.codecString)
    }

    @Test
    fun anIdrWithoutParameterSetsCarriesTheLatest() {
        val assembler = AccessUnitAssembler()
        assembler.assemble(stream4(SPS, PPS))
        val idr = assembler.assemble(stream4(IDR))
        assertTrue(idr.idr)
        assertTrue(idr.paramSets)
        assertEquals(listOf(5), idr.nalTypes)
        assertEquals(listOf(7, 8, 5), types(idr.accessUnit))
        assertArrayEquals(stream4(SPS, PPS, IDR), idr.accessUnit)
    }

    @Test
    fun anIdrWithItsOwnParameterSetsKeepsThem() {
        val assembler = AccessUnitAssembler()
        val idr = assembler.assemble(stream3(SPS, PPS, IDR))
        assertTrue(idr.paramSets)
        assertArrayEquals(stream4(SPS, PPS, IDR), idr.accessUnit)
        assertFourByteStartCodes(checkNotNull(idr.accessUnit))
    }

    @Test
    fun anIdrMissingItsPpsGetsBothInOrder() {
        val assembler = AccessUnitAssembler()
        assembler.learnConfig(stream4(SPS), stream4(PPS))
        val newSps = bytes(0x67, 0x42, 0xE0, 0x1E, 0xDA)
        val idr = assembler.assemble(stream4(newSps, IDR))
        assertArrayEquals(stream4(newSps, PPS, IDR), idr.accessUnit)
    }

    @Test
    fun parameterSetsFollowALeadingAccessUnitDelimiter() {
        val assembler = AccessUnitAssembler()
        assembler.assemble(stream4(SPS, PPS))
        val idr = assembler.assemble(stream4(AUD, IDR))
        assertEquals(listOf(9, 7, 8, 5), types(idr.accessUnit))
    }

    @Test
    fun otherFramesGoAsTheyAreWithFourByteStartCodes() {
        val assembler = AccessUnitAssembler()
        assembler.assemble(stream4(SPS, PPS))
        val frame = assembler.assemble(stream3(SLICE))
        assertFalse(frame.idr)
        assertFalse(frame.paramSets)
        assertArrayEquals(stream4(SLICE), frame.accessUnit)
    }

    @Test
    fun anIdrBeforeAnyParameterSetGoesWithoutThem() {
        val idr = AccessUnitAssembler().assemble(stream4(IDR))
        assertNotNull(idr.accessUnit)
        assertTrue(idr.idr)
        assertFalse(idr.paramSets)
    }

    @Test
    fun theCodecConfigOfTheOutputFormatCounts() {
        val assembler = AccessUnitAssembler()
        assertTrue(assembler.learnConfig(stream4(SPS), stream4(PPS)))
        assertFalse(assembler.learnConfig(stream4(SPS), null))
        assertEquals(listOf(7, 8, 5), types(assembler.assemble(stream4(IDR)).accessUnit))
    }
}
