@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import org.junit.Assert.assertEquals
import org.junit.Test

class YuvScalerTest {
    /** A planar 4:2:0 image whose planes hold [luma] and [chroma] of each pixel position. */
    private fun image(w: Int, h: Int, luma: (Int, Int) -> Int, chroma: (Int, Int) -> Int = luma) =
        listOf(
            plane(w, h, luma),
            plane(w / 2, h / 2, chroma),
            plane(w / 2, h / 2) { x, y -> 255 - chroma(x, y) },
        )

    private fun plane(w: Int, h: Int, value: (Int, Int) -> Int): YuvPlane {
        val buffer = ByteBuffer.allocate(w * h)
        for (y in 0 until h) for (x in 0 until w) buffer.put(y * w + x, value(x, y).toByte())
        return YuvPlane(buffer, w, 1)
    }

    private fun at(p: YuvPlane, x: Int, y: Int) =
        p.buffer.get(p.offset + y * p.rowStride + x * p.pixelStride).toInt() and 0xFF

    @Test
    fun aSameSizeCropCopiesExactly() {
        val src = image(8, 8, { x, y -> x * 16 + y })
        val dst = image(4, 4, { _, _ -> 0 })
        scaleYuv420(src, PixelRect(2, 2, 6, 6), dst, 4, 4)
        for (y in 0 until 4) for (x in 0 until 4) assertEquals(
            (x + 2) * 16 + y + 2,
            at(dst[0], x, y),
        )
        for (y in 0 until 2) for (x in 0 until 2) {
            assertEquals((x + 1) * 16 + y + 1, at(dst[1], x, y))
            assertEquals(255 - ((x + 1) * 16 + y + 1), at(dst[2], x, y))
        }
    }

    @Test
    fun halvingAveragesNeighbours() {
        val src = image(8, 8, { x, _ -> x * 32 })
        val dst = image(4, 4, { _, _ -> 0 })
        scaleYuv420(src, PixelRect(0, 0, 8, 8), dst, 4, 4)
        assertEquals(listOf(16, 80, 144, 208), (0 until 4).map { at(dst[0], it, 2) })
    }

    @Test
    fun nothingIsFlipped() {
        val src = image(8, 8, { x, y -> if (x == 0 && y == 0) 255 else 0 })
        val dst = image(8, 8, { _, _ -> 7 })
        scaleYuv420(src, PixelRect(0, 0, 8, 8), dst, 8, 8)
        assertEquals(255, at(dst[0], 0, 0))
        assertEquals(0, at(dst[0], 7, 0))
        assertEquals(0, at(dst[0], 0, 7))
    }

    @Test
    fun stridesAndInterleavedChromaAreHonoured() {
        val src = image(8, 8, { x, y -> x * 16 + y }, { x, y -> 100 + x * 10 + y })
        val layout = RawYuvLayout(21, stride = 16, sliceHeight = 16)
        val buffer = ByteBuffer.allocate(layout.size)
        val dst = layout.planes(buffer)
        scaleYuv420(src, PixelRect(0, 0, 8, 8), dst, 8, 8)
        assertEquals(7 * 16 + 5, buffer.get(5 * 16 + 7).toInt() and 0xFF)
        assertEquals(0, buffer.get(5 * 16 + 8).toInt())
        val uv = 16 * 16
        assertEquals(100 + 3 * 10 + 2, buffer.get(uv + 2 * 16 + 3 * 2).toInt() and 0xFF)
        assertEquals(255 - (100 + 3 * 10 + 2), buffer.get(uv + 2 * 16 + 3 * 2 + 1).toInt() and 0xFF)
    }
}
