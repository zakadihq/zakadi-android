@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.app.Activity
import androidx.test.platform.app.InstrumentationRegistry
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.AnnexB
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.ParameterSetsChanged
import dev.zakadi.sdk.encode.avcCodecString
import dev.zakadi.sdk.encode.pickAvcEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

/**
 * Both capture paths on the device's front camera (CI: the emulator's, started with `-camera-front
 * emulated`) at every rung size of the ladder of spec 01 section 1.5: upright, unmirrored 3:4
 * frames, the encoder re-created on each size change, and every frame and buffer seen by the
 * listener (spec 07 sections 7.7, 7.18 and 7.19).
 */
class CapturePipelineTest {
    private val ladder =
        listOf(
            Rung(0, 480, 640, 20, 900),
            Rung(1, 480, 640, 20, 600),
            Rung(2, 480, 640, 15, 400),
            Rung(3, 336, 448, 12, 250),
            Rung(4, 288, 384, 10, 150),
        )
    private lateinit var activity: Activity

    @Before
    fun startActivity() {
        activity = startCameraActivity()
    }

    @After
    fun finishActivity() {
        activity.finish()
    }

    @Test
    fun pathAFeedsEveryRungSize() {
        check(run(CapturePath.A), CapturePath.A)
    }

    @Test
    fun pathBFeedsEveryRungSize() {
        check(run(CapturePath.B), CapturePath.B)
    }

    private fun run(path: CapturePath): RecordingListener {
        val events = RecordingListener()
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val pipeline = CapturePipeline(context, events)
        val preference =
            if (pickAvcEncoder(preferSoftware = false) != null) EncoderPreference.HARDWARE
            else EncoderPreference.SOFTWARE
        pipeline.start(CaptureConfig(ladder, startRung = 2, encoder = preference, path = path))
        try {
            for (index in 2..4) {
                if (index > 2) pipeline.setRung(index)
                awaitUnits(events, ladder[index], 10)
            }
            pipeline.requestKeyframe()
            await("the IDR answering a keyframe request") {
                events.all<KeyframeEvent>().any { it.kind == KeyframeEvent.Kind.ANSWERED }
            }
            pipeline.setDecimation(1)
            await("decimation drops") {
                events.all<FrameEvent>().any { it.reason == DropReason.DECIMATION }
            }
            assertTrue("encQueue", pipeline.stats().encQueue >= 0)
        } finally {
            val stopped = CountDownLatch(1)
            pipeline.stop { stopped.countDown() }
            assertTrue("stopped", stopped.await(10, TimeUnit.SECONDS))
        }
        return events
    }

    private fun awaitUnits(events: RecordingListener, rung: Rung, count: Int) {
        await("$count access units at ${rung.width}x${rung.height}", 45_000) {
            assertTrue(
                "errors: ${events.all<CaptureError>()}",
                events.all<CaptureError>().isEmpty(),
            )
            val encoder =
                events.all<EncoderStarted>().lastOrNull {
                    it.width == rung.width && it.height == rung.height
                } ?: return@await false
            events.all<OutputBuffer>().count {
                it.encoderId == encoder.encoderId && it.accessUnit != null
            } >= count
        }
    }

    private fun check(events: RecordingListener, path: CapturePath) {
        assertEquals("fallbacks", emptyList<PathFallback>(), events.all<PathFallback>())
        assertEquals("errors", emptyList<CaptureError>(), events.all<CaptureError>())

        // One encoder per rung size, each 3:4, each released before the next starts.
        val started = events.all<EncoderStarted>()
        assertEquals(
            listOf(480 to 640, 336 to 448, 288 to 384),
            started.map { it.width to it.height },
        )
        for (encoder in started) assertEquals(encoder.width * 4, encoder.height * 3)
        val stopped = events.all<EncoderStopped>()
        assertEquals(started.map { it.encoderId }, stopped.map { it.encoderId })
        for (i in 1 until started.size) {
            assertTrue(events.events.indexOf(stopped[i - 1]) < events.events.indexOf(started[i]))
        }
        assertEquals(
            listOf(true, true, true),
            events.all<RungEvent>().map { it.recreatesEncoder },
        )

        // Every frame captured is submitted or dropped, and every access unit is a submitted frame.
        val frames = events.all<FrameEvent>()
        val captured = frames.count { it.kind == FrameEvent.Kind.CAPTURED }
        val submitted = frames.filter { it.kind == FrameEvent.Kind.SUBMITTED }
        val dropped = frames.filter { it.kind == FrameEvent.Kind.DROPPED }
        assertEquals(captured, submitted.size + dropped.size)
        val submittedUs = submitted.map { it.timestampNanos / 1000 }.toSet()
        val units = events.all<OutputBuffer>().filter { it.accessUnit != null }
        for (unit in units) {
            assertTrue("pts ${unit.presentationTimeUs}", unit.presentationTimeUs in submittedUs)
        }

        // Annex-B with 4-byte start codes, SPS and PPS on each IDR, no configuration alone.
        for (buffer in events.all<OutputBuffer>()) {
            val au = buffer.accessUnit
            if (au == null) {
                assertTrue(buffer.nalTypes.none(AnnexB::isVcl))
                continue
            }
            assertFourByteStartCodes(au)
            val types = AnnexB.split(au).map(AnnexB::type)
            if (buffer.idr) {
                assertTrue("SPS and PPS before the IDR: $types", buffer.paramSets)
                assertTrue(types.indexOf(7) in 0 until types.indexOf(5))
                assertTrue(types.indexOf(8) in 0 until types.indexOf(5))
            }
        }
        for (encoder in started) {
            val first = units.first { it.encoderId == encoder.encoderId }
            assertTrue("the first access unit of an encoder is an IDR", first.idr)
        }

        // config.video: the first SPS's codec string, unmirrored, the camera's rotation.
        val camera = events.all<CameraEvent>().last()
        assertEquals(path, camera.path)
        assertTrue(camera.rotationDegrees in listOf(0, 90, 180, 270))
        if (path == CapturePath.B) {
            assertEquals(camera.rotationDegrees % 180 == 90, camera.height > camera.width)
        }
        val configs = events.all<VideoConfig>()
        assertEquals(started.map { it.encoderId }, configs.map { it.encoderId })
        for (config in configs) {
            val sps =
                events.all<ParameterSetsChanged>().first { it.encoderId == config.encoderId }.sps
            assertEquals(avcCodecString(checkNotNull(sps)), config.codec)
            assertTrue(
                config.codec,
                Regex("avc1\\.42[0-9A-F]{2}(0[0-9A-F]|1[0-9A-F])").matches(config.codec),
            )
            assertFalse(config.mirrored)
            assertEquals(camera.rotationDegrees, config.rotation)
            assertEquals(config.width * 4, config.height * 3)
        }

        // The pacer holds each rung's rate.
        for ((encoder, rung) in started.zip(listOf(ladder[2], ladder[3], ladder[4]))) {
            val times =
                submitted.filter { it.encoderId == encoder.encoderId }.map { it.timestampNanos }
            assertTrue(times.zipWithNext().all { (a, b) -> b > a })
            for (t in times) {
                val inSecond = times.count { it >= t && it < t + 1_000_000_000L }
                assertTrue(
                    "$inSecond frames in a second at ${rung.fps} fps",
                    inSecond <= rung.fps + 1,
                )
            }
        }

        // Auto-exposure at the oval centre without auto-cancel, +0.3 EV in the device's range.
        val exposure = events.all<ExposureEvent>().single()
        assertFalse(exposure.autoCancel)
        assertEquals(0.5f, exposure.pointX)
        assertEquals(0.5f, exposure.pointY)
        if (exposure.compensationSupported) {
            val range = checkNotNull(exposure.range)
            val step = checkNotNull(exposure.step)
            assertEquals(
                exposureCompensationIndex(0.3, step, range.first, range.last),
                exposure.index,
            )
            assertEquals(checkNotNull(exposure.index) * step, checkNotNull(exposure.ev), 1e-9)
        }

        // t0 is the first frame the camera delivered.
        val clock = events.all<ClockEvent>().single()
        assertEquals(
            frames.first { it.kind == FrameEvent.Kind.CAPTURED }.timestampNanos,
            clock.t0Nanos,
        )
    }

    private fun assertFourByteStartCodes(au: ByteArray) {
        assertTrue(au.size > 4 && au[0].toInt() == 0 && au[1].toInt() == 0 && au[2].toInt() == 0)
        assertEquals(1, au[3].toInt())
        for (i in 0 until au.size - 2) {
            if (au[i].toInt() == 0 && au[i + 1].toInt() == 0 && au[i + 2].toInt() == 1) {
                assertTrue("a 3-byte start code at $i", i >= 1 && au[i - 1].toInt() == 0)
            }
        }
    }
}
