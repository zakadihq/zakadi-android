@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer

/** One plane of a YUV 4:2:0 image: bytes from [offset], with a row stride and a pixel stride. */
@InternalZakadiApi
class YuvPlane(
    val buffer: ByteBuffer,
    val rowStride: Int,
    val pixelStride: Int,
    val offset: Int = 0,
)

/**
 * Path B of spec 07 section 7.18: crops [crop] (luma pixels, even edges) out of the 4:2:0 image
 * [src] (planes Y, U, V) and scales it bilinearly into the [width] x [height] image [dst],
 * honouring each plane's row and pixel strides. Nothing is flipped or rotated: CameraX has already
 * turned the image upright.
 */
@InternalZakadiApi
fun scaleYuv420(
    src: List<YuvPlane>,
    crop: PixelRect,
    dst: List<YuvPlane>,
    width: Int,
    height: Int,
) {
    require(src.size == 3 && dst.size == 3) { "a 4:2:0 image has 3 planes" }
    require(crop.width > 1 && crop.height > 1 && width > 1 && height > 1) { "empty crop or target" }
    scalePlane(src[0], crop.left, crop.top, crop.width, crop.height, dst[0], width, height)
    for (p in 1..2) {
        scalePlane(
            src[p],
            crop.left / 2,
            crop.top / 2,
            crop.width / 2,
            crop.height / 2,
            dst[p],
            width / 2,
            height / 2,
        )
    }
}

/** Bilinear scaling with 8-bit fractions, sampling at pixel centres. */
private fun scalePlane(
    s: YuvPlane,
    sx0: Int,
    sy0: Int,
    sw: Int,
    sh: Int,
    d: YuvPlane,
    dw: Int,
    dh: Int,
) {
    val xs = IntArray(dw)
    val xf = IntArray(dw)
    positions(sw, dw, xs, xf)
    val ys = IntArray(dh)
    val yf = IntArray(dh)
    positions(sh, dh, ys, yf)
    for (y in 0 until dh) {
        val row0 = s.offset + (sy0 + ys[y]) * s.rowStride
        val row1 = s.offset + (sy0 + minOf(ys[y] + 1, sh - 1)) * s.rowStride
        val fy = yf[y]
        val out = d.offset + y * d.rowStride
        for (x in 0 until dw) {
            val c0 = (sx0 + xs[x]) * s.pixelStride
            val c1 = (sx0 + minOf(xs[x] + 1, sw - 1)) * s.pixelStride
            val fx = xf[x]
            val top = s.at(row0 + c0) * (256 - fx) + s.at(row0 + c1) * fx
            val bottom = s.at(row1 + c0) * (256 - fx) + s.at(row1 + c1) * fx
            val value = (top * (256 - fy) + bottom * fy + 32768) shr 16
            d.buffer.put(out + x * d.pixelStride, value.toByte())
        }
    }
}

private fun YuvPlane.at(index: Int): Int = buffer.get(index).toInt() and 0xFF

/**
 * For each of [dst] target pixels, the source pixel left of or above its centre and the 8-bit
 * fraction towards the next one, clamped to the [src] source pixels.
 */
private fun positions(src: Int, dst: Int, index: IntArray, fraction: IntArray) {
    val max = (src - 1) * 256L
    for (i in 0 until dst) {
        val pos = ((2L * i + 1) * src * 256 / (2L * dst) - 128).coerceIn(0L, max)
        index[i] = (pos shr 8).toInt()
        fraction[i] = (pos and 0xFF).toInt()
    }
}
