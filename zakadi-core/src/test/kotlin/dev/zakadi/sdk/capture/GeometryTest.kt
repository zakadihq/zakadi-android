@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeometryTest {
    /** The matrix `SurfaceTexture.getTransformMatrix` gives a buffer with no transform. */
    private val flip = Affine.flipVertical().toGl()

    @Test
    fun centreCropsAre3x4WithEvenEdges() {
        assertEquals(PixelRect(140, 0, 500, 480), centerCrop3x4(640, 480))
        assertEquals(PixelRect(0, 0, 480, 640), centerCrop3x4(480, 640))
        assertEquals(PixelRect(370, 0, 910, 720), centerCrop3x4(1280, 720))
        assertEquals(PixelRect(0, 160, 720, 1120), centerCrop3x4(720, 1280))
        val odd = centerCrop3x4(1920, 1080)
        assertEquals(PixelRect(554, 0, 1364, 1080), odd)
        assertEquals(odd.width * 4, odd.height * 3)
    }

    /**
     * Where the output's corners (top left, top right, bottom left, bottom right of the encoded
     * frame) sample the buffer, in buffer pixels with y down: the texture's first row is the top.
     */
    private fun corners(g: FrameGeometry, st: FloatArray = flip): List<Pair<Double, Double>> {
        val m = Affine.fromGl(encoderTextureMatrix(g, st))
        return listOf(0.0 to 0.0, 1.0 to 0.0, 0.0 to 1.0, 1.0 to 1.0).map { (x, y) ->
            val (s, t) = m.map(x, 1 - y).let { it[0] to it[1] }
            round(s * g.bufferWidth) to round(t * g.bufferHeight)
        }
    }

    private fun round(v: Double) = Math.round(v * 1000) / 1000.0

    /** Positive when output right and down keep their handedness in the buffer: no mirror. */
    private fun unmirrored(g: FrameGeometry, st: FloatArray = flip): Boolean {
        val c = corners(g, st)
        val rx = c[1].first - c[0].first to c[1].second - c[0].second
        val dy = c[2].first - c[0].first to c[2].second - c[0].second
        return rx.first * dy.second - rx.second * dy.first > 0
    }

    private fun geometry(w: Int, h: Int, rotation: Int, crop: PixelRect = PixelRect(0, 0, w, h)) =
        FrameGeometry(w, h, crop, rotation, false, 270, true)

    @Test
    fun rotation90TurnsALandscapeBufferUpright() {
        val g = geometry(640, 480, 90)
        assertEquals(
            listOf(0.0 to 480.0, 0.0 to 0.0, 640.0 to 480.0, 640.0 to 0.0),
            corners(g),
        )
        assertTrue(unmirrored(g))
    }

    @Test
    fun rotation270TurnsALandscapeBufferUpright() {
        val g = geometry(640, 480, 270)
        assertEquals(
            listOf(640.0 to 0.0, 640.0 to 480.0, 0.0 to 0.0, 0.0 to 480.0),
            corners(g),
        )
        assertTrue(unmirrored(g))
    }

    @Test
    fun rotation0And180CropTheCentre3x4() {
        assertEquals(
            listOf(140.0 to 0.0, 500.0 to 0.0, 140.0 to 480.0, 500.0 to 480.0),
            corners(geometry(640, 480, 0)),
        )
        assertEquals(
            listOf(500.0 to 480.0, 140.0 to 480.0, 500.0 to 0.0, 140.0 to 0.0),
            corners(geometry(640, 480, 180)),
        )
        assertTrue(unmirrored(geometry(640, 480, 0)))
        assertTrue(unmirrored(geometry(640, 480, 180)))
    }

    @Test
    fun aWideBufferIsCroppedAfterTheRotation() {
        assertEquals(
            listOf(1120.0 to 0.0, 1120.0 to 720.0, 160.0 to 0.0, 160.0 to 720.0),
            corners(geometry(1280, 720, 270)),
        )
    }

    @Test
    fun cameraXsCropRectIsHonoured() {
        val g = geometry(1280, 720, 90, PixelRect(160, 0, 1120, 720))
        assertEquals(
            listOf(160.0 to 720.0, 160.0 to 0.0, 1120.0 to 720.0, 1120.0 to 0.0),
            corners(g),
        )
    }

    @Test
    fun theCameraTransformOfAFrontCameraIsUndoneWithoutMirroring() {
        val g = FrameGeometry(640, 480, PixelRect(0, 0, 640, 480), 270, true, 270, true)
        val st =
            (Affine.flipVertical() * Affine.rotateAboutCentre(270) * Affine.mirrorHorizontal())
                .toGl()
        assertEquals(corners(geometry(640, 480, 270)), corners(g, st))
        assertTrue(unmirrored(g, st))
    }

    @Test
    fun theCameraTransformOfABackCameraIsUndone() {
        val g = FrameGeometry(640, 480, PixelRect(0, 0, 640, 480), 90, true, 90, false)
        val st = (Affine.flipVertical() * Affine.rotateAboutCentre(90)).toGl()
        assertEquals(corners(geometry(640, 480, 90)), corners(g, st))
    }

    @Test
    fun affineArithmetic() {
        val t = Affine.translate(0.25, 0.5) * Affine.rotate(90) * Affine.scale(2.0, 3.0)
        val p = t.map(1.0, 1.0)
        val back = t.inverse().map(p[0], p[1])
        assertEquals(1.0, back[0], 1e-12)
        assertEquals(1.0, back[1], 1e-12)
        val gl = Affine.fromGl(t.toGl())
        assertEquals(t.map(0.3, 0.7)[0], gl.map(0.3, 0.7)[0], 1e-6)
    }
}
