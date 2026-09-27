@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertThrows
import org.junit.Test

class RawYuvLayoutTest {
    private fun offsets(layout: RawYuvLayout) =
        layout.planes(ByteBuffer.allocate(layout.size)).map {
            Triple(it.offset, it.rowStride, it.pixelStride)
        }

    @Test
    fun semiPlanarWithTheFrameSizeWhenTheFormatSaysNothing() {
        val layout = RawYuvLayout.of(21, 480, 640, emptyMap())
        assertEquals(480, layout.stride)
        assertEquals(640, layout.sliceHeight)
        assertEquals(460_800, layout.size)
        assertEquals(
            listOf(Triple(0, 480, 1), Triple(307_200, 480, 2), Triple(307_201, 480, 2)),
            offsets(layout),
        )
    }

    @Test
    fun planarWithTheStrideAndSliceHeightOfTheInputFormat() {
        val layout =
            RawYuvLayout.of(19, 480, 640, mapOf("stride" to "496", "slice-height" to "656"))
        val luma = 496 * 656
        assertEquals(
            listOf(Triple(0, 496, 1), Triple(luma, 248, 1), Triple(luma + 248 * 328, 248, 1)),
            offsets(layout),
        )
    }

    @Test
    fun strideAndSliceHeightArePaddedTo16AndNeverBelowTheFrame() {
        val padded =
            RawYuvLayout.of(21, 336, 448, mapOf("stride" to "340", "slice-height" to "450"))
        assertEquals(352, padded.stride)
        assertEquals(464, padded.sliceHeight)
        val tooSmall = RawYuvLayout.of(21, 336, 448, mapOf("stride" to "0", "slice-height" to "8"))
        assertEquals(336, tooSmall.stride)
        assertEquals(448, tooSmall.sliceHeight)
    }

    @Test
    fun theFirstSemiPlanarOrPlanarFormatIsPicked() {
        assertEquals(21, RawYuvLayout.pick(intArrayOf(0x7F420888, 21, 19)))
        assertEquals(19, RawYuvLayout.pick(intArrayOf(0x7F000789, 19)))
        assertNull(RawYuvLayout.pick(intArrayOf(0x7FA30C04, 0x7F420888)))
        assertThrows(IllegalArgumentException::class.java) { RawYuvLayout(0x7F420888, 16, 16) }
    }
}
