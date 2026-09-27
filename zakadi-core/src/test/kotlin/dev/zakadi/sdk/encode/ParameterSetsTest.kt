@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

class ParameterSetsTest {
    @Test
    fun theCodecStringComesFromTheSps() {
        assertEquals("avc1.42E01F", avcCodecString(SPS))
        assertEquals("avc1.42C01E", avcCodecString(bytes(0x67, 0x42, 0xC0, 0x1E, 0x95)))
    }

    @Test
    fun emulationPreventionBytesAreNotPartOfTheCodecString() {
        assertEquals("avc1.640000", avcCodecString(bytes(0x67, 0x64, 0x00, 0x00, 0x03, 0x1F)))
    }

    @Test
    fun onlyAnSpsHasACodecString() {
        assertNull(avcCodecString(PPS))
        assertNull(avcCodecString(bytes(0x67, 0x42)))
    }

    @Test
    fun theFirstSpsKeepsTheCodecString() {
        val sets = ParameterSets()
        assertTrue(sets.learn(listOf(SPS, PPS)))
        assertEquals("avc1.42E01F", sets.codecString)
        val high = bytes(0x67, 0x64, 0x00, 0x28, 0xAC)
        assertTrue(sets.learn(listOf(high)))
        assertArrayEquals(high, sets.sps)
        assertEquals("avc1.42E01F", sets.codecString)
    }

    @Test
    fun onlyChangesAreReported() {
        val sets = ParameterSets()
        assertTrue(sets.learn(listOf(SPS)))
        assertNull(sets.both)
        assertFalse(sets.learn(listOf(SPS, IDR)))
        assertTrue(sets.learn(listOf(PPS)))
        assertEquals(listOf(7, 8), sets.both?.map(AnnexB::type))
        assertFalse(sets.learn(listOf(SPS, PPS)))
    }
}
