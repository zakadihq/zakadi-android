@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AnnexBTest {
    @Test
    fun splitsAtThreeAndFourByteStartCodes() {
        val stream = bytes(0, 0, 0, 1) + SPS + bytes(0, 0, 1) + PPS + bytes(0, 0, 0, 1) + IDR
        val nals = AnnexB.split(stream)
        assertEquals(listOf(7, 8, 5), nals.map(AnnexB::type))
        assertArrayEquals(SPS, nals[0])
        assertArrayEquals(PPS, nals[1])
        assertArrayEquals(IDR, nals[2])
    }

    @Test
    fun trailingZerosBelongToTheNextStartCode() {
        val stream = bytes(0, 0, 1) + SLICE + bytes(0, 0, 0, 0, 0, 1) + IDR + bytes(0, 0)
        val nals = AnnexB.split(stream)
        assertArrayEquals(SLICE, nals[0])
        assertArrayEquals(IDR, nals[1])
    }

    @Test
    fun bytesBeforeTheFirstStartCodeAreDropped() {
        val nals = AnnexB.split(bytes(0x12, 0x34) + stream4(SLICE))
        assertEquals(1, nals.size)
        assertArrayEquals(SLICE, nals[0])
    }

    @Test
    fun aStreamWithoutStartCodeIsOneNalUnit() {
        assertArrayEquals(SLICE, AnnexB.split(SLICE).single())
        assertTrue(AnnexB.split(ByteArray(0)).isEmpty())
    }

    @Test
    fun readsTheRangeOfAByteBufferAndLeavesItsPosition() {
        val stream = stream3(SPS, PPS)
        val buffer = ByteBuffer.allocate(stream.size + 5)
        buffer.position(3)
        buffer.put(stream)
        buffer.position(1)
        val nals = AnnexB.split(buffer, 3, stream.size)
        assertEquals(listOf(7, 8), nals.map(AnnexB::type))
        assertEquals(1, buffer.position())
    }

    @Test
    fun joinsWithFourByteStartCodes() {
        val joined = AnnexB.join4(listOf(SPS, IDR))
        assertArrayEquals(bytes(0, 0, 0, 1) + SPS + bytes(0, 0, 0, 1) + IDR, joined)
        assertFourByteStartCodes(joined)
    }

    @Test
    fun slicesAreTypesOneToFive() {
        assertTrue((1..5).all(AnnexB::isVcl))
        assertFalse(listOf(6, 7, 8, 9).any(AnnexB::isVcl))
    }
}
