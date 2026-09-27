@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CameraClockDomain
import dev.zakadi.sdk.capture.ClockEvent
import dev.zakadi.sdk.capture.FrameEvent
import dev.zakadi.sdk.capture.RungEvent
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import kotlinx.serialization.json.JsonObject
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** The summaries of a `run_end` line as Z-064's Spec defines them, on synthetic events. */
class ProbeSummaryTest {
    /** A probe of one 3 s constant run at rung 2 that makes no keyframe request of its own. */
    private class OneRun {
        val lines = ArrayList<String>()
        val calls = ArrayList<String>()
        val schedule =
            ProbeSchedule(
                number = 1,
                ladder = ProbeSchedule.LADDER,
                runs =
                    listOf(
                        ScheduledRun(
                            RunMode.CONSTANT,
                            ProbeSchedule.LADDER[2],
                            3_000,
                            keyframeRequests = false,
                        )
                    ),
                gopMs = 2000,
                keyframeDelayMs = 1000,
            )
        val probe =
            EncoderProbe(
                schedule,
                device(),
                ProbeArgs(),
                null,
                object : ProbeTarget {
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
                },
                FakeReadings(),
            ) {
                lines += it
            }

        init {
            probe.start(0)
            probe.onRung(RungEvent(1 * MS, 2, 2, 480, 640, 15, 400, true))
            probe.onEncoderStarted(
                EncoderStarted(
                    2 * MS,
                    ENCODER,
                    "c2.fake.avc.encoder",
                    true,
                    480,
                    640,
                    15,
                    400_000,
                    2000,
                    true,
                    0,
                    1..20_000_000,
                    1 * MS,
                )
            )
            probe.onClock(ClockEvent(3 * MS, CAMERA_BASE, CameraClockDomain.BOOTTIME, 1))
        }

        fun frame(frame: Int) {
            val at = SECOND + frame * 50 * MS
            probe.onFrame(
                FrameEvent(
                    at,
                    FrameEvent.Kind.SUBMITTED,
                    CAMERA_BASE + frame * 50 * MS,
                    0,
                    2,
                    encoderId = ENCODER,
                )
            )
        }

        fun request(atNanos: Long, kind: KeyframeEvent.Kind = KeyframeEvent.Kind.REQUESTED) {
            probe.onKeyframe(KeyframeEvent(atNanos, ENCODER, kind))
        }

        fun output(frame: Int, bytes: Int, key: Boolean) {
            val at = SECOND + frame * 50 * MS + 10 * MS
            val types = if (key) listOf(5) else listOf(1)
            probe.onOutputBuffer(
                OutputBuffer(
                    at,
                    ENCODER,
                    (CAMERA_BASE + frame * 50 * MS) / 1000,
                    0,
                    bytes,
                    if (key) 1 else 0,
                    key,
                    false,
                    false,
                    types,
                    key,
                    ByteArray(bytes + 4),
                    false,
                )
            )
        }

        /** Ends the run at [atNanos] and stops the encoder: the `run_end` line. */
        fun finish(atNanos: Long): List<JsonObject> {
            probe.advance(atNanos)
            assertEquals("stop", calls.last())
            probe.onEncoderStopped(EncoderStopped(atNanos + 1 * MS, ENCODER, true))
            probe.onStopped(atNanos + 2 * MS)
            return parseLog(lines)
        }

        companion object {
            const val ENCODER = 7
            const val CAMERA_BASE = 40 * SECOND
        }
    }

    /**
     * Three seconds at 20 fps: an `in` line per frame at 1 s plus 50 ms a frame and its `out` 10 ms
     * later, IDRs at frames 0, 11 and 24 (5000 bytes, the others 1000), requests after frames 9 and
     * 19 answered 2 and 5 frames later, one after frame 50 never answered and repeated, and no
     * output for frames 30 to 41: a 600 ms stall.
     */
    @Test
    fun theSummariesFollowTheirDefinitionsOnSyntheticEvents() {
        val run = OneRun()
        for (frame in 0 until 60) {
            run.frame(frame)
            val at = SECOND + frame * 50 * MS
            if (frame in listOf(9, 19, 50)) run.request(at + 1 * MS)
            if (frame in 30..41) continue
            val key = frame in listOf(0, 11, 24)
            run.output(frame, if (key) 5000 else 1000, key)
        }
        run.request(SECOND + 50 * 50 * MS + 506 * MS, KeyframeEvent.Kind.REPEATED)
        val lines = run.finish(SECOND + 10 * MS + 3 * SECOND)
        val end = lines.single { it.kind == "run_end" }
        assertEquals(60, end.int("in"))
        assertEquals(48, end.int("out"))
        assertEquals(3, end.int("idr"))
        assertEquals(3, end.int("idr_bare"))
        assertEquals(480_000.0 * 1000 / 2_950_000, end.double("delivered_kbps"), 0.001)
        assertEquals((109.0 + 259.0) / 2, end.double("kf_latency_ms"), 1e-9)
        assertEquals((2.0 + 5.0) / 2, end.double("kf_frames"), 1e-9)
        assertEquals(8, end.int("min_fps"))
        assertEquals(
            listOf(1 to false, 2 to false, 3 to false, 3 to true),
            lines.filter { it.kind == "kf_req" }.map { it.int("n") to it.bool("repeat") },
        )
        assertEquals(emptyList<String>(), run.calls.filter { it == "keyframe" })
    }

    @Test
    fun deliveredBytesStartAtTheFirstKeyOutput() {
        val records =
            listOf(
                ProbeRecord.output(0, 0, 9000, key = false),
                ProbeRecord.output(0, 100_000, 1000, key = true),
                ProbeRecord.output(0, 1_100_000, 1000, key = false),
            )
        assertEquals(16.0, ProbeSummary.of(records).deliveredKbps!!, 1e-9)
        assertEquals("the window ends before the last out", 1, ProbeSummary.of(records).minFps)
    }

    @Test
    fun repeatedRequestsAreLeftOut() {
        val records =
            listOf(
                ProbeRecord.output(0, 0, 1, key = true),
                ProbeRecord.keyframeRequest(10_000, repeat = false),
                ProbeRecord.keyframeRequest(20_000, repeat = true),
                ProbeRecord.input(30_000, 50_000),
                ProbeRecord.output(40_000, 50_000, 1, key = true),
            )
        val summary = ProbeSummary.of(records)
        assertEquals(30.0, summary.kfLatencyMs!!, 1e-9)
        assertEquals(1.0, summary.kfFrames!!, 1e-9)
    }

    @Test
    fun noLinesGiveNoSummaries() {
        assertEquals(ProbeSummary(null, null, null, null), ProbeSummary.of(emptyList()))
        val unanswered =
            listOf(
                ProbeRecord.output(0, 0, 1, key = true),
                ProbeRecord.keyframeRequest(1, repeat = false),
            )
        assertNull(ProbeSummary.of(unanswered).kfLatencyMs)
        assertNull(ProbeSummary.of(unanswered).kfFrames)
        assertNull("no window fits in 0 us", ProbeSummary.of(unanswered).minFps)
        assertNull("no span", ProbeSummary.of(unanswered).deliveredKbps)
    }
}
