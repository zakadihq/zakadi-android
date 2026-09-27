@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.opengl.EGLSurface
import android.opengl.GLES20
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.EglCore
import dev.zakadi.sdk.capture.RecordingListener
import dev.zakadi.sdk.capture.YuvPlane
import dev.zakadi.sdk.capture.await
import dev.zakadi.sdk.capture.centerCrop3x4
import dev.zakadi.sdk.capture.scaleYuv420
import java.nio.ByteBuffer
import java.util.concurrent.CountDownLatch
import java.util.concurrent.FutureTask
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * The AVC encoder of spec 07 section 7.19 on the device's own codec (CI: the emulator's software
 * encoder), fed by OpenGL frames through its input surface or by YUV images through its buffers.
 */
class AvcEncoderTest {
    private val encoderThread = HandlerThread("lv-venc-test")
    private val glThread = HandlerThread("lv-gl-test")
    private lateinit var enc: Handler
    private lateinit var gl: Handler
    private val info: MediaCodecInfo =
        checkNotNull(
            pickAvcEncoder(preferSoftware = false) ?: pickAvcEncoder(preferSoftware = true)
        )
    private val events = RecordingListener()
    private val frameNanos = 66_666_667L
    private val base = 10_000_000_000L

    @Before
    fun startThreads() {
        encoderThread.start()
        glThread.start()
        enc = Handler(encoderThread.looper)
        gl = Handler(glThread.looper)
    }

    @After
    fun stopThreads() {
        encoderThread.quitSafely()
        glThread.quitSafely()
    }

    private fun surfaceEncoder(): AvcEncoder =
        AvcEncoder.create(info, AvcFormatSpec(480, 640, 15, 400_000, 2000, true), enc, events)

    /** Draws frames into [encoder]'s input surface on the GL thread. */
    private inner class Feeder(private val encoder: AvcEncoder) {
        private lateinit var egl: EglCore
        private lateinit var surface: EGLSurface
        var frames = 0
            private set

        init {
            onGl {
                egl = EglCore()
                surface = egl.createWindowSurface(checkNotNull(encoder.inputSurface))
                egl.makeCurrent(surface)
            }
        }

        /**
         * Draws [count] frames with a square that moves, 15 fps apart; returns their timestamps.
         */
        fun draw(count: Int): List<Long> = onGl {
            (0 until count).map {
                val i = frames++
                GLES20.glClearColor((i % 7) / 7f, 0.3f, 0.6f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glEnable(GLES20.GL_SCISSOR_TEST)
                GLES20.glScissor((i * 16) % 400, (i * 24) % 560, 80, 80)
                GLES20.glClearColor(1f, 1f, 1f, 1f)
                GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
                GLES20.glDisable(GLES20.GL_SCISSOR_TEST)
                val ts = base + i * frameNanos
                egl.setPresentationTime(surface, ts)
                assertTrue("swap", egl.swapBuffers(surface))
                encoder.onFrameSubmitted()
                ts
            }
        }

        fun release() = onGl {
            egl.makeOffscreenCurrent()
            egl.releaseSurface(surface)
            egl.release()
        }
    }

    private fun stop(encoder: AvcEncoder) {
        val stopped = CountDownLatch(1)
        encoder.stop { stopped.countDown() }
        assertTrue("stopped", stopped.await(5, TimeUnit.SECONDS))
    }

    private fun units() = events.all<OutputBuffer>().filter { it.accessUnit != null }

    @Test
    fun theFormatAsksForWhat719Lists() {
        val encoder = surfaceEncoder()
        stop(encoder)
        val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
        val cbr =
            checkNotNull(caps.encoderCapabilities)
                .isBitrateModeSupported(MediaCodecInfo.EncoderCapabilities.BITRATE_MODE_CBR)
        val cb =
            Build.VERSION.SDK_INT >= 27 &&
                caps.profileLevels.any { it.profile == AVC_PROFILE_CONSTRAINED_BASELINE }
        val asked = events.all<FormatEvent>().first { it.kind == FormatEvent.Kind.ASKED }.values
        assertEquals(0, events.all<FormatEvent>().first().step)
        assertEquals(if (cbr) "2" else "1", asked["bitrate-mode"])
        assertEquals(if (cb) "65536" else "1", asked["profile"])
        assertEquals("512", asked["level"])
        assertEquals("1", asked["latency"])
        assertEquals("0", asked["priority"])
        assertEquals(if (Build.VERSION.SDK_INT >= 29) "0" else null, asked["max-bframes"])
        assertEquals("480", asked["width"])
        assertEquals("640", asked["height"])
        val kinds = events.all<FormatEvent>().map { it.kind }
        assertTrue(
            kinds.containsAll(
                listOf(FormatEvent.Kind.INPUT_READ_BACK, FormatEvent.Kind.OUTPUT_READ_BACK)
            )
        )
        val refused = events.all<FormatEvent>().count { it.kind == FormatEvent.Kind.REFUSED }
        assertEquals(refused, encoder.step)
        assertEquals(encoder.step, events.all<EncoderStarted>().single().step)
    }

    @Test
    fun theOutputIsAnnexBWithParameterSetsOnEveryIdr() {
        val encoder = surfaceEncoder()
        val feeder = Feeder(encoder)
        val submitted = feeder.draw(45).map { it / 1000 }.toSet()
        await("access units") { units().size >= 30 }
        feeder.release()
        stop(encoder)
        assertTrue(events.all<EncoderStopped>().single().drained)
        for (buffer in events.all<OutputBuffer>()) {
            val au = buffer.accessUnit
            if (au == null) {
                assertTrue("no slice in ${buffer.nalTypes}", buffer.nalTypes.none(AnnexB::isVcl))
                continue
            }
            assertTrue(buffer.presentationTimeUs in submitted)
            assertTrue(au.copyOf(4).contentEquals(byteArrayOf(0, 0, 0, 1)))
            for (i in 1 until au.size - 2) {
                if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                    assertEquals("a 3-byte start code at $i", 0, au[i - 1].toInt())
                }
            }
            val types = AnnexB.split(au).map(AnnexB::type)
            assertEquals(buffer.idr, 5 in types)
            if (buffer.idr) {
                assertTrue(buffer.paramSets)
                assertTrue(types.indexOf(7) in 0 until types.indexOf(5))
                assertTrue(types.indexOf(8) in 0 until types.indexOf(5))
            }
        }
        assertTrue("the first unit is an IDR", units().first().idr)
        val firstSps =
            events.all<OutputBuffer>().firstNotNullOf { b ->
                b.accessUnit?.let { AnnexB.split(it) }?.firstOrNull { AnnexB.type(it) == 7 }
            }
        val codec = checkNotNull(encoder.codecString)
        assertEquals(avcCodecString(firstSps), codec)
        assertTrue(codec, Regex("avc1\\.[0-9A-F]{6}").matches(codec))
        assertTrue("level at most 3.1: $codec", codec.takeLast(2).toInt(16) <= 31)
        if (encoder.step < 2) assertEquals("Baseline: $codec", "42", codec.substring(5, 7))
    }

    @Test
    fun aKeyframeRequestIsAnsweredOrRepeatedOnce() {
        val encoder = surfaceEncoder()
        val feeder = Feeder(encoder)
        val last = feeder.draw(5).last() / 1000
        await("the first five frames") { units().any { it.presentationTimeUs == last } }
        encoder.requestKeyframe()
        await("the request") { events.all<KeyframeEvent>().isNotEmpty() }
        Thread.sleep(700)
        val beforeFrames = events.all<KeyframeEvent>().map { it.kind }
        feeder.draw(10)
        await("the answer") {
            events.all<KeyframeEvent>().any { it.kind == KeyframeEvent.Kind.ANSWERED }
        }
        val idrs = units().count { it.idr }
        feeder.release()
        stop(encoder)
        assertEquals(
            listOf(KeyframeEvent.Kind.REQUESTED, KeyframeEvent.Kind.REPEATED),
            beforeFrames,
        )
        val answer = events.all<KeyframeEvent>().last()
        assertEquals(KeyframeEvent.Kind.ANSWERED, answer.kind)
        assertTrue(checkNotNull(answer.msToIdr) >= 700)
        assertTrue(idrs >= 2)
    }

    @Test
    fun bitrateRequestsClampToTheCodecRange() {
        val encoder = surfaceEncoder()
        val feeder = Feeder(encoder)
        feeder.draw(3)
        encoder.requestBitrate(Int.MAX_VALUE)
        encoder.requestBitrate(1)
        encoder.requestBitrate(300_000)
        await("three bitrate events") { events.all<BitrateEvent>().size == 3 }
        feeder.release()
        stop(encoder)
        val range = encoder.bitrateRange
        assertEquals(
            listOf(
                clampBitrate(Int.MAX_VALUE, range),
                clampBitrate(1, range),
                clampBitrate(300_000, range),
            ),
            events.all<BitrateEvent>().map { it.appliedBps },
        )
        assertEquals(range.last, events.all<BitrateEvent>()[0].appliedBps)
        assertEquals(maxOf(1, range.first), events.all<BitrateEvent>()[1].appliedBps)
    }

    @Test
    fun bufferInputTakesYuvImages() {
        val spec = AvcFormatSpec(336, 448, 12, 250_000, 2000, surfaceInput = false)
        val encoder = AvcEncoder.create(info, spec, enc, events)
        assertNull(encoder.inputSurface)
        val source =
            listOf(640 to 480, 320 to 240, 320 to 240).map { (w, h) ->
                YuvPlane(
                    ByteBuffer.allocate(w * h).apply {
                        for (i in 0 until w * h) put(i, (i % 251).toByte())
                    },
                    w,
                    1,
                )
            }
        var queued = 0
        val end = System.currentTimeMillis() + 20_000
        while (queued < 20 && System.currentTimeMillis() < end) {
            val index = encoder.dequeueInput()
            if (index == null) {
                Thread.sleep(5)
                continue
            }
            val image = encoder.inputImage(index)
            assertNotNull("a flexible YUV input image", image)
            val planes =
                checkNotNull(image).planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) }
            scaleYuv420(source, centerCrop3x4(640, 480), planes, 336, 448)
            assertTrue(
                encoder.queueInput(index, 336 * 448 * 3 / 2, (base + queued * frameNanos) / 1000)
            )
            queued++
        }
        await("access units from YUV input") { units().size >= 10 }
        stop(encoder)
        assertEquals(20, queued)
        assertTrue(units().first().idr)
        assertFalse(events.all<EncoderError>().any())
    }

    private fun <T> onGl(block: () -> T): T {
        val task = FutureTask(block)
        gl.post(task)
        return task.get(10, TimeUnit.SECONDS)
    }
}
