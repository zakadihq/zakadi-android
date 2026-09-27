@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.media.MediaFormat
import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer

/**
 * The layout of a raw 4:2:0 encoder input buffer for when `getInputImage` offers no image (spec 07
 * section 7.18, path B): semi-planar (Y, then interleaved U and V) or planar (Y, U, V), with the
 * stride and slice height of the codec's input format, padded to 16.
 */
@InternalZakadiApi
class RawYuvLayout(
    val colorFormat: Int,
    val stride: Int,
    val sliceHeight: Int,
) {
    init {
        require(colorFormat in SEMI_PLANAR || colorFormat in PLANAR) {
            "colour format $colorFormat is neither semi-planar nor planar"
        }
    }

    /** Bytes of one frame. */
    val size: Int
        get() = stride * sliceHeight * 3 / 2

    /** The Y, U and V planes of [buffer] under this layout. */
    fun planes(buffer: ByteBuffer): List<YuvPlane> {
        val luma = stride * sliceHeight
        return if (colorFormat in SEMI_PLANAR) {
            listOf(
                YuvPlane(buffer, stride, 1, 0),
                YuvPlane(buffer, stride, 2, luma),
                YuvPlane(buffer, stride, 2, luma + 1),
            )
        } else {
            val chroma = stride / 2
            listOf(
                YuvPlane(buffer, stride, 1, 0),
                YuvPlane(buffer, chroma, 1, luma),
                YuvPlane(buffer, chroma, 1, luma + chroma * (sliceHeight / 2)),
            )
        }
    }

    companion object {
        /** `COLOR_FormatYUV420SemiPlanar` and `COLOR_FormatYUV420PackedSemiPlanar`. */
        val SEMI_PLANAR: Set<Int> = setOf(21, 39)

        /** `COLOR_FormatYUV420Planar` and `COLOR_FormatYUV420PackedPlanar`. */
        val PLANAR: Set<Int> = setOf(19, 20)

        /** The first semi-planar or planar format among a codec's [colorFormats], if any. */
        fun pick(colorFormats: IntArray): Int? = colorFormats.firstOrNull {
            it in SEMI_PLANAR || it in PLANAR
        }

        /**
         * The layout of [colorFormat] for a [width] x [height] frame: `stride` and `slice-height`
         * of [inputFormat] (a read-back format) where they cover the frame, else the frame's own,
         * each rounded up to a multiple of 16.
         */
        fun of(
            colorFormat: Int,
            width: Int,
            height: Int,
            inputFormat: Map<String, String>,
        ): RawYuvLayout {
            val stride =
                inputFormat[MediaFormat.KEY_STRIDE]?.toIntOrNull()?.takeIf { it >= width } ?: width
            val slice =
                inputFormat[MediaFormat.KEY_SLICE_HEIGHT]?.toIntOrNull()?.takeIf { it >= height }
                    ?: height
            return RawYuvLayout(colorFormat, align16(stride), align16(slice))
        }

        private fun align16(value: Int): Int = (value + 15) and 15.inv()
    }
}
