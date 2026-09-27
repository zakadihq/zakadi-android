@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi

/** A rectangle in pixels, right and bottom exclusive, as `android.graphics.Rect`. */
@InternalZakadiApi
data class PixelRect(val left: Int, val top: Int, val right: Int, val bottom: Int) {
    val width: Int
        get() = right - left

    val height: Int
        get() = bottom - top
}

/**
 * The largest centred 3:4 portrait rectangle of a [width] x [height] image, with even edges so that
 * 4:2:0 chroma planes crop with it (spec 05 section 5.3: crop centre, aspect 3:4).
 */
@InternalZakadiApi
fun centerCrop3x4(width: Int, height: Int): PixelRect {
    require(width > 0 && height > 0) { "size ${width}x$height is empty" }
    return if (width.toLong() * 4 > height.toLong() * 3) {
        val w = (height * 3 / 4) and 1.inv()
        val left = ((width - w) / 2) and 1.inv()
        PixelRect(left, 0, left + w, height and 1.inv())
    } else {
        val h = (width * 4 / 3) and 1.inv()
        val top = ((height - h) / 2) and 1.inv()
        PixelRect(0, top, width and 1.inv(), top + h)
    }
}

/**
 * How camera buffers reach the path A OpenGL pass (spec 07 section 7.18): the buffer size and the
 * `SurfaceRequest.TransformationInfo` of CameraX, with the camera's sensor rotation and facing.
 */
@InternalZakadiApi
data class FrameGeometry(
    val bufferWidth: Int,
    val bufferHeight: Int,
    /** `TransformationInfo.getCropRect()`, in buffer pixels. */
    val crop: PixelRect,
    /** `TransformationInfo.getRotationDegrees()`: the clockwise rotation that makes it upright. */
    val rotationDegrees: Int,
    /** `TransformationInfo.hasCameraTransform()`: the texture matrix carries the camera's. */
    val hasCameraTransform: Boolean,
    /** `CameraInfo.getSensorRotationDegrees()`. */
    val sensorRotationDegrees: Int,
    val frontFacing: Boolean,
)

/**
 * The texture matrix of the path A pass (column-major, applied as `uTexMatrix * aTextureCoord` to a
 * quad whose texture coordinates run 0 to 1 from the bottom left): [surfaceTextureMatrix] times the
 * inverse of the transform the camera is predicted to have put in it (a vertical flip, then the
 * sensor rotation and a front camera's mirror when [FrameGeometry.hasCameraTransform]), times the
 * wanted one: a vertical flip, the clockwise rotation, and the centred 3:4 crop of the crop
 * rectangle, never a mirror. This is the arithmetic of CameraX's `SurfaceOutputImpl` with mirroring
 * off and the 3:4 crop, so the frames come out upright, unmirrored and 3:4 (spec 05 section 5.3).
 */
@InternalZakadiApi
fun encoderTextureMatrix(geometry: FrameGeometry, surfaceTextureMatrix: FloatArray): FloatArray {
    val g = geometry
    require(g.rotationDegrees % 90 == 0) { "rotation ${g.rotationDegrees} is not a right angle" }
    val quarterTurns = Math.floorMod(g.rotationDegrees, 360) / 90
    val rw = if (quarterTurns % 2 == 0) g.bufferWidth else g.bufferHeight
    val rh = if (quarterTurns % 2 == 0) g.bufferHeight else g.bufferWidth
    val rotated = rotateClockwise(g.crop, g.bufferWidth, g.bufferHeight, quarterTurns)
    val crop = centredAspect(rotated, 3.0, 4.0)
    val wanted =
        Affine.flipVertical() *
            Affine.rotateAboutCentre(g.rotationDegrees) *
            Affine.translate(crop[0] / rw, (rh - crop[3] - crop[1]) / rh) *
            Affine.scale(crop[2] / rw, crop[3] / rh)
    var predicted = Affine.flipVertical()
    if (g.hasCameraTransform) {
        predicted = predicted * Affine.rotateAboutCentre(g.sensorRotationDegrees)
        if (g.frontFacing) predicted = predicted * Affine.mirrorHorizontal()
    }
    return (Affine.fromGl(surfaceTextureMatrix) * predicted.inverse() * wanted).toGl()
}

/** [rect] of a [width] x [height] image after turning the image clockwise [quarterTurns] times. */
private fun rotateClockwise(
    rect: PixelRect,
    width: Int,
    height: Int,
    quarterTurns: Int,
): PixelRect =
    when (quarterTurns) {
        0 -> rect
        1 -> PixelRect(height - rect.bottom, rect.left, height - rect.top, rect.right)
        2 ->
            PixelRect(
                width - rect.right,
                height - rect.bottom,
                width - rect.left,
                height - rect.top,
            )
        else -> PixelRect(rect.top, width - rect.right, rect.bottom, width - rect.left)
    }

/** The largest centred [aw]:[ah] rectangle of [rect], as left, top, width and height. */
private fun centredAspect(rect: PixelRect, aw: Double, ah: Double): DoubleArray {
    val w = rect.width.toDouble()
    val h = rect.height.toDouble()
    return if (w * ah > h * aw) {
        val cw = h * aw / ah
        doubleArrayOf(rect.left + (w - cw) / 2, rect.top.toDouble(), cw, h)
    } else {
        val ch = w * ah / aw
        doubleArrayOf(rect.left.toDouble(), rect.top + (h - ch) / 2, w, ch)
    }
}

/**
 * A 2D affine transform of texture coordinates, `x' = a x + c y + e` and `y' = b x + d y + f`, in
 * the order of `android.opengl.Matrix`: `p * q` applies `q` first.
 */
internal class Affine(
    val a: Double,
    val b: Double,
    val c: Double,
    val d: Double,
    val e: Double,
    val f: Double,
) {
    operator fun times(q: Affine): Affine =
        Affine(
            a * q.a + c * q.b,
            b * q.a + d * q.b,
            a * q.c + c * q.d,
            b * q.c + d * q.d,
            a * q.e + c * q.f + e,
            b * q.e + d * q.f + f,
        )

    fun inverse(): Affine {
        val det = a * d - b * c
        require(det != 0.0) { "the transform is singular" }
        val ia = d / det
        val ib = -b / det
        val ic = -c / det
        val id = a / det
        return Affine(ia, ib, ic, id, -(ia * e + ic * f), -(ib * e + id * f))
    }

    fun map(x: Double, y: Double): DoubleArray = doubleArrayOf(a * x + c * y + e, b * x + d * y + f)

    /** The column-major 4x4 matrix of OpenGL. */
    fun toGl(): FloatArray =
        FloatArray(16).also {
            it[0] = a.toFloat()
            it[1] = b.toFloat()
            it[4] = c.toFloat()
            it[5] = d.toFloat()
            it[10] = 1f
            it[12] = e.toFloat()
            it[13] = f.toFloat()
            it[15] = 1f
        }

    companion object {
        val IDENTITY = Affine(1.0, 0.0, 0.0, 1.0, 0.0, 0.0)

        fun fromGl(m: FloatArray): Affine {
            require(m.size >= 16) { "a 4x4 matrix has 16 values, not ${m.size}" }
            return Affine(
                m[0].toDouble(),
                m[1].toDouble(),
                m[4].toDouble(),
                m[5].toDouble(),
                m[12].toDouble(),
                m[13].toDouble(),
            )
        }

        fun translate(x: Double, y: Double) = Affine(1.0, 0.0, 0.0, 1.0, x, y)

        fun scale(x: Double, y: Double) = Affine(x, 0.0, 0.0, y, 0.0, 0.0)

        /** Counter-clockwise by [degrees] about the origin, a right angle exactly. */
        fun rotate(degrees: Int): Affine {
            val (cos, sin) =
                when (Math.floorMod(degrees, 360)) {
                    0 -> 1.0 to 0.0
                    90 -> 0.0 to 1.0
                    180 -> -1.0 to 0.0
                    270 -> 0.0 to -1.0
                    else -> throw IllegalArgumentException("rotation $degrees is not a right angle")
                }
            return Affine(cos, sin, -sin, cos, 0.0, 0.0)
        }

        /** `MatrixExt.preVerticalFlip(m, 0.5f)` of CameraX. */
        fun flipVertical() = translate(0.0, 0.5) * scale(1.0, -1.0) * translate(0.0, -0.5)

        /** `MatrixExt.preRotate(m, degrees, 0.5f, 0.5f)` of CameraX. */
        fun rotateAboutCentre(degrees: Int) =
            translate(0.5, 0.5) * rotate(degrees) * translate(-0.5, -0.5)

        /** The horizontal mirror of CameraX's `SurfaceOutputImpl`. */
        fun mirrorHorizontal() = translate(1.0, 0.0) * scale(-1.0, 1.0)
    }
}
