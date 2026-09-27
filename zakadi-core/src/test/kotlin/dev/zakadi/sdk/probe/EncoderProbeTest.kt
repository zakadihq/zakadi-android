@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CaptureError
import dev.zakadi.sdk.capture.PathFallback
import dev.zakadi.sdk.encode.PPS
import dev.zakadi.sdk.encode.SPS
import kotlinx.serialization.json.JsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** A whole log of the encoder probe over a simulated pipeline, and the ways a probe ends. */
class EncoderProbeTest {
    private val short = ProbeSchedule.ONE.shortened(10)

    @Test
    fun everyLineParsesWithItsKeysInOrder() {
        val lines = SimulatedProbe(short, args = ProbeArgs(divisor = 10)).run()
        assertEquals("device", lines.first().kind)
        assertEquals("end", lines.last().kind)
        assertEquals(1, lines.count { it.kind == "device" })
        assertEquals(1, lines.count { it.kind == "end" })
        for (line in lines) {
            assertEquals(LogReader.KEYS.getValue(line.kind), line.keys.toList())
            assertEquals(1, line.int("v"))
            assertTrue(line.long("t_us") >= 0)
        }
        for (encoder in lines.first().getValue("encoders") as JsonArray) {
            assertEquals(LogReader.ENCODER_KEYS, encoder.obj().keys.toList())
        }
        for (run in lines.filter { it.kind == "run" }) {
            assertEquals(LogReader.CAMERA_KEYS, run.getValue("camera").obj().keys.toList())
            assertEquals("boottime", run.getValue("camera").obj().str("clock"))
            assertEquals("c2.fake.avc.encoder", run.str("encoder"))
            assertEquals("constrained_baseline", run.str("profile"))
            assertEquals("3.1", run.str("level"))
            assertEquals("cbr", run.str("bitrate_mode"))
        }
        assertEquals(10, lines.first().getValue("args").obj().int("divisor"))

        // Each run's lines follow its run line and end with its run_end.
        for (index in 0..5) {
            val kinds = lines.filter {
                it.kind !in setOf("device", "end") && it.int("run") == index
            }
            assertEquals("run", kinds.first().kind)
            assertEquals("run_end", kinds.last().kind)
            assertEquals(1, kinds.count { it.kind == "run" })
            assertEquals(1, kinds.count { it.kind == "run_end" })
            val params = kinds.filter { it.kind == "params" }
            assertEquals(listOf("csd"), params.map { it.str("source") })
            assertEquals("avc1.42E01F", params.single().str("codec"))
            val totals = kinds.last()
            assertEquals(kinds.count { it.kind == "in" }, totals.int("in"))
            assertEquals(kinds.count { it.kind == "out" }, totals.int("out"))
            assertEquals(kinds.count { it.kind == "out" && it.bool("key") }, totals.int("idr"))
            assertEquals(0, totals.int("idr_bare"))
            assertTrue(totals.isNull("error"))
            assertTrue("the first out is an IDR", kinds.first { it.kind == "out" }.bool("key"))
            for (out in kinds.filter { it.kind == "out" }) assertEquals(0, out.int("sc3"))
        }
        // The first run's pts_us counts from its first submitted frame.
        assertEquals(0L, lines.first { it.kind == "in" }.long("pts_us"))
    }

    @Test
    fun theLogHoldsNoMediaAndNoIdentifierBeyondTheDeviceFacts() {
        val sim = SimulatedProbe(short)
        val lines = sim.run()
        val text = sim.lines.joinToString("\n")
        assertFalse("frame bytes as text", text.contains(FRAME_MARKER.decodeToString()))
        assertFalse("frame bytes as hex", text.contains(LogLines.hex(FRAME_MARKER)))
        for (line in lines) assertEquals(LogReader.KEYS.getValue(line.kind), line.keys.toList())
        for (params in lines.filter { it.kind == "params" }) {
            assertEquals(LogLines.hex(SPS), params.str("sps"))
            assertEquals(LogLines.hex(PPS), params.str("pps"))
        }
        // The device line names the model, SoC and build, and nothing else of the phone or user.
        val device = lines.first()
        val strings =
            device.keys
                .filter { device.str(it) != null }
                .associateWith { device.str(it) }
                .filterKeys { it != "kind" }
        assertEquals(
            mapOf(
                "platform" to "android",
                "phone" to "TECNO TECNO KI5k",
                "soc" to "Mediatek MT6769",
                "os" to "14",
                "os_build" to "zakadi/test/fake:14/UP1A/1:user/release-keys",
                "tier" to "S",
                "camera_level" to "LIMITED",
                "thermal" to "nominal",
            ),
            strings,
        )
    }

    @Test
    fun noEncoderOfThePreferenceEndsTheProbeAsUnsupported() {
        val lines = ArrayList<String>()
        val target = RecordingTarget()
        val probe =
            EncoderProbe(short, device(), ProbeArgs(), null, target, FakeReadings()) {
                lines += it
            }
        probe.start(0)
        probe.onError(CaptureError(1 * MS, CaptureError.Kind.NO_ENCODER, "no SOFTWARE AVC encoder"))
        assertEquals(listOf("stop"), target.calls)
        assertFalse(probe.finished)
        probe.onStopped(2 * MS)
        assertTrue(probe.finished)
        val parsed = parseLog(lines)
        assertEquals(listOf("device", "end"), parsed.map { it.kind })
        assertEquals("unsupported_device", parsed.last().str("reason"))
        assertEquals(0, parsed.last().int("runs"))
        assertEquals(2000L, parsed.last().long("t_us"))
    }

    @Test
    fun noFrontCameraEndsTheProbeAsUnsupported() {
        val lines = ArrayList<String>()
        val target = RecordingTarget()
        val probe =
            EncoderProbe(short, device(), ProbeArgs(), null, target, FakeReadings()) {
                lines += it
            }
        probe.start(0)
        probe.unsupported()
        probe.onStopped(1 * MS)
        val parsed = parseLog(lines)
        assertEquals(listOf("device", "end"), parsed.map { it.kind })
        assertEquals("unsupported_device", parsed.last().str("reason"))
        assertNull(probe.nextDue())
    }

    @Test
    fun aCaptureFailureEndsTheProbeWithTheRunsError() {
        val sim = SimulatedProbe(short)
        sim.pipeline.inject(SimulatedProbe.START + 3 * SECOND) {
            sim.probe.onError(
                CaptureError(sim.pipeline.now, CaptureError.Kind.CAMERA, "bind: gone")
            )
        }
        val lines = sim.run()
        val ends = lines.filter { it.kind == "run_end" }
        assertEquals("error", lines.last().str("reason"))
        assertEquals(ends.size, lines.last().int("runs"))
        assertEquals("CAMERA: bind: gone", ends.last().str("error"))
        assertEquals(
            ends.map { it.int("run") },
            lines.filter { it.kind == "run" }.map { it.int("run") },
        )
    }

    @Test
    fun aFallbackToPathBDuringARunEndsTheProbe() {
        val sim = SimulatedProbe(short)
        sim.pipeline.inject(SimulatedProbe.START + 1 * SECOND) {
            sim.probe.onPathFallback(PathFallback(sim.pipeline.now, "EGL: lost"))
        }
        val lines = sim.run()
        assertEquals("error", lines.last().str("reason"))
        val end = lines.single { it.kind == "run_end" }
        assertEquals("fallback: EGL: lost", end.str("error"))
    }

    @Test
    fun aRunWithoutFramesEndsWithATimeout() {
        val sim = SimulatedProbe(short, pipeline = FakePipeline(cameraDelivers = false))
        val lines = sim.run()
        val ends = lines.filter { it.kind == "run_end" }
        assertEquals(List(6) { "timeout" }, ends.map { it.str("error") })
        assertEquals(List(6) { 0 }, ends.map { it.int("in") })
        val runs = lines.filter { it.kind == "run" }
        assertEquals((0..5).toList(), runs.map { it.int("run") })
        assertTrue(runs.all { it.isNull("encoder") && it.isNull("t0_us") })
        assertEquals("done", lines.last().str("reason"))
        // Each run ends 10 s after its duration, counted from when it began.
        assertEquals(12_000_000L, ends.first().long("t_us"))
    }

    @Test
    fun ticksCountEachSecondOfARun() {
        val lines = SimulatedProbe(short).run()
        val run = lines.first { it.kind == "run" && it.int("run") == 2 }
        val ticks = lines.filter { it.kind == "tick" && it.int("run") == 2 }
        assertTrue("${ticks.size} ticks", ticks.size >= 6)
        for ((n, tick) in ticks.withIndex()) {
            assertEquals(run.long("t_us") + (n + 1) * 1_000_000L, tick.long("t_us"))
            // The first second holds the run's first frame and the one 999.99999 ms after it.
            assertEquals(if (n == 0) 31 else 30, tick.int("captured"))
            assertEquals(if (n == 0) 16 else 15, tick.int("submitted"))
            assertTrue(tick.int("encoded") in 14..16)
            assertTrue(tick.long("enc_queue") in 0L..2L)
            assertEquals(40L, tick.long("cpu_ms"))
            assertTrue(tick.isNull("enc_dropped"))
            assertEquals("nominal", tick.str("thermal"))
        }
        val drops = ticks.map { it.long("pre_encode_drops") }
        assertEquals("pacing drops accumulate", drops.sorted(), drops)
        assertTrue(drops.last() >= 15L * ticks.size - 1)
    }

    private class RecordingTarget : ProbeTarget {
        val calls = ArrayList<String>()

        override fun setRung(index: Int) {
            calls += "setRung $index"
        }

        override fun requestKeyframe() {
            calls += "keyframe"
        }

        override fun requestBitrate(kbps: Int) {
            calls += "bitrate $kbps"
        }

        override fun stop() {
            calls += "stop"
        }
    }
}
