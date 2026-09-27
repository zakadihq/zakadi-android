@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.graphics.Color
import android.graphics.Paint
import android.graphics.SurfaceTexture
import android.opengl.GLES20
import android.os.Handler
import android.os.HandlerThread
import android.view.Surface
import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The path A pass on the device's GPU: a landscape buffer with a colour in each quadrant goes
 * through [encoderTextureMatrix] at each rotation, and the 3:4 portrait output shows the quadrants
 * where a clockwise rotation puts them, never mirrored (spec 05 section 5.3, spec 07 section 7.18).
 */
class OesRendererTest {
    private val red = Color.RED
    private val green = Color.GREEN
    private val blue = Color.BLUE
    private val white = Color.WHITE

    /** Output quadrants (top left, top right, bottom left, bottom right) for each rotation. */
    private val expected =
        mapOf(
            0 to listOf(red, green, blue, white),
            90 to listOf(blue, red, white, green),
            180 to listOf(white, blue, green, red),
            270 to listOf(green, white, red, blue),
        )

    @Test
    fun eachRotationComesOutUprightAndUnmirrored() {
        val thread = HandlerThread("lv-gl-test").apply { start() }
        val handler = Handler(thread.looper)
        try {
            for ((rotation, quadrants) in expected) {
                assertEquals(
                    "rotation $rotation",
                    quadrants.map(::name),
                    render(handler, rotation).map(::name),
                )
            }
        } finally {
            thread.quitSafely()
        }
    }

    /** The colours at the output's quadrant centres after drawing at [rotation]. */
    private fun render(handler: Handler, rotation: Int): List<Int> {
        val width = 640
        val height = 480
        val outW = 480
        val outH = 640
        lateinit var egl: EglCore
        lateinit var renderer: OesRenderer
        lateinit var texture: SurfaceTexture
        var name = 0
        onThread(handler) {
            egl = EglCore()
            assertTrue("pbuffers", egl.pbuffers)
            egl.makeOffscreenCurrent()
            renderer = OesRenderer()
            name = renderer.newTexture()
            texture = SurfaceTexture(name).apply { setDefaultBufferSize(width, height) }
        }
        val available = CountDownLatch(1)
        texture.setOnFrameAvailableListener({ available.countDown() }, handler)
        val surface = Surface(texture)
        val canvas = surface.lockCanvas(null)
        val paint = Paint()
        for ((i, color) in listOf(red, green, blue, white).withIndex()) {
            paint.color = color
            val x = (i % 2) * width / 2f
            val y = (i / 2) * height / 2f
            canvas.drawRect(x, y, x + width / 2f, y + height / 2f, paint)
        }
        surface.unlockCanvasAndPost(canvas)
        assertTrue("frame available", available.await(5, TimeUnit.SECONDS))
        return onThread(handler) {
            texture.updateTexImage()
            val st = FloatArray(16).also { texture.getTransformMatrix(it) }
            val geometry =
                FrameGeometry(
                    width,
                    height,
                    PixelRect(0, 0, width, height),
                    rotation,
                    hasCameraTransform = false,
                    sensorRotationDegrees = 0,
                    frontFacing = true,
                )
            val target = egl.createPbufferSurface(outW, outH)
            egl.makeCurrent(target)
            renderer.draw(name, encoderTextureMatrix(geometry, st), outW, outH)
            val pixels = ByteBuffer.allocateDirect(outW * outH * 4).order(ByteOrder.nativeOrder())
            GLES20.glReadPixels(0, 0, outW, outH, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
            val colours =
                listOf(
                        outW / 4 to outH / 4,
                        3 * outW / 4 to outH / 4,
                        outW / 4 to 3 * outH / 4,
                        3 * outW / 4 to 3 * outH / 4,
                    )
                    .map { (x, y) ->
                        val at = ((outH - 1 - y) * outW + x) * 4
                        Color.rgb(
                            pixels.get(at).toInt() and 0xFF,
                            pixels.get(at + 1).toInt() and 0xFF,
                            pixels.get(at + 2).toInt() and 0xFF,
                        )
                    }
            egl.makeOffscreenCurrent()
            egl.releaseSurface(target)
            renderer.deleteTexture(name)
            renderer.release()
            texture.release()
            surface.release()
            egl.release()
            colours
        }
    }

    private fun name(colour: Int): String {
        val r = Color.red(colour) > 128
        val g = Color.green(colour) > 128
        val b = Color.blue(colour) > 128
        return when {
            r && g && b -> "white"
            r && !g && !b -> "red"
            !r && g && !b -> "green"
            !r && !g && b -> "blue"
            else -> "#" + Integer.toHexString(colour)
        }
    }

    private fun <T> onThread(handler: Handler, block: () -> T): T {
        val task = FutureTask(block)
        handler.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
