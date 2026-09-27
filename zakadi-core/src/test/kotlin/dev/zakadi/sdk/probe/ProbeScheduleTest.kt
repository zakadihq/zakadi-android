@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.device.DeviceIdentity
import dev.zakadi.sdk.device.DeviceQuirk
import dev.zakadi.sdk.device.deviceCaps
import dev.zakadi.sdk.device.tightenedBy
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/** Schedule 1 of Z-064's Spec: its runs, and what a run does on the times of its IDRs alone. */
class ProbeScheduleTest {
    @Test
    fun scheduleOneRunsTheLadderThenTheStep() {
        val schedule = ProbeSchedule.ONE
        assertEquals(1, schedule.number)
        assertEquals(2000, schedule.gopMs)
        assertEquals(1000L, schedule.keyframeDelayMs)
        assertEquals(1, schedule.divisor)
        assertEquals(listOf(0, 1, 2, 3, 4, 2), schedule.runs.map { it.rung.index })
        assertEquals(
            listOf(20_000L, 20_000L, 60_000L, 20_000L, 20_000L, 18_000L),
            schedule.runs.map { it.durationMs },
        )
        assertEquals(
            List(5) { RunMode.CONSTANT } + RunMode.STEP,
            schedule.runs.map { it.mode },
        )
        assertEquals(List(5) { true } + false, schedule.runs.map { it.keyframeRequests })
        assertEquals(
            listOf(RateStep(6_000, 200), RateStep(12_000, 400)),
            schedule.runs[5].rateSteps,
        )
        assertEquals(ProbeSchedule.LADDER[2], schedule.runs[5].rung)
        assertEquals(
            listOf(
                listOf(480, 640, 20, 900),
                listOf(480, 640, 20, 600),
                listOf(480, 640, 15, 400),
                listOf(336, 448, 12, 250),
                listOf(288, 384, 10, 150),
            ),
            ProbeSchedule.LADDER.map { listOf(it.width, it.height, it.fps, it.videoKbps) },
        )
    }

    @Test
    fun aShortenedScheduleDividesDurationsAndStepTimes() {
        val short = ProbeSchedule.ONE.shortened(10)
        assertEquals(10, short.divisor)
        assertEquals(
            listOf(2_000L, 2_000L, 6_000L, 2_000L, 2_000L, 1_800L),
            short.runs.map { it.durationMs },
        )
        assertEquals(listOf(600L, 1_200L), short.runs[5].rateSteps.map { it.atMs })
        assertEquals(2000, short.gopMs)
        assertEquals(1000L, short.keyframeDelayMs)
    }

    @Test
    fun aConstantRunRequestsAKeyframeASecondAfterEveryIdrThatAnsweredNone() {
        val run = RunControl(ProbeSchedule.ONE.runs[0], 1000)
        assertEquals("nothing before the first IDR", emptyList<Any>(), run.due(0))
        assertNull(run.nextDue())
        run.onIdr(0)
        assertEquals(1000 * MS, run.nextDue())
        assertEquals(emptyList<Any>(), run.due(950 * MS))
        assertEquals(listOf(RunControl.Action.RequestKeyframe), run.due(1000 * MS))
        assertEquals("one request per IDR", emptyList<Any>(), run.due(1050 * MS))
        run.onIdr(1050 * MS)
        assertEquals("an answering IDR schedules none", emptyList<Any>(), run.due(2100 * MS))
        run.onIdr(3050 * MS)
        assertEquals(emptyList<Any>(), run.due(4000 * MS))
        assertEquals(listOf(RunControl.Action.RequestKeyframe), run.due(4050 * MS))
        assertEquals(emptyList<Any>(), run.due(19_950 * MS))
        assertEquals(listOf(RunControl.Action.End), run.due(20_000 * MS))
        assertTrue(run.ended)
        assertEquals(emptyList<Any>(), run.due(20_050 * MS))
        assertNull(run.nextDue())
    }

    @Test
    fun theStepRunChangesTheBitrateAtSixAndTwelveSeconds() {
        val run = RunControl(ProbeSchedule.ONE.runs[5], 1000)
        run.onIdr(500 * MS)
        val actions = LinkedHashMap<Long, List<RunControl.Action>>()
        var time = 500 * MS
        while (time <= 18_500 * MS) {
            val now = run.due(time)
            if (now.isNotEmpty()) actions[time / MS] = now
            run.onIdr(time)
            time += 50 * MS
        }
        assertEquals(
            mapOf(
                6_500L to listOf(RunControl.Action.SetBitrate(200)),
                12_500L to listOf(RunControl.Action.SetBitrate(400)),
                18_500L to listOf(RunControl.Action.End),
            ),
            actions,
        )
    }

    @Test
    fun aRunChangeThroughAnotherSizeReCreatesTheEncoder() {
        val ladder = ProbeSchedule.LADDER
        assertEquals(4, detour(ladder, 480 to 640, 480 to 640))
        assertEquals(4, detour(ladder, 480 to 640, 336 to 448))
        assertEquals(0, detour(ladder, 336 to 448, 288 to 384))
        assertNull(detour(ladder.take(2), 480 to 640, 480 to 640))
    }

    /**
     * The whole of schedule 1 through a simulated pipeline on a fake clock, on a phone of tier L
     * that a quirk caps further: every rung runs at the ladder's size, rate and bitrate on an
     * encoder of its own, for its duration from its first IDR, with a keyframe request 1000 ms
     * after every IDR that answered none and the step run's bitrate steps; the caps appear in the
     * log and never reach the pipeline.
     */
    @Test
    fun scheduleOneRunsOnAFakeClockWithTheCapsLoggedAndNotApplied() {
        val quirk =
            DeviceQuirk(platform = "android", modelPrefix = "TECNO", maxFps = 12, maxRung = 3)
        val tierL = report(lowRam = true)
        val caps =
            deviceCaps(tierL.facts).tightenedBy(listOf(quirk), DeviceIdentity("TECNO", "KI5k", 14))
        val sim = SimulatedProbe(report = tierL, caps = caps)
        val lines = sim.run()

        // The caps are logged: tier L, and best rung 3 at 12 fps from the quirk.
        val device = lines.first()
        assertEquals("device", device.kind)
        assertEquals("L", device.str("tier"))
        val logged = device.getValue("args").obj().getValue("caps").obj()
        assertEquals(3, logged.int("best_rung"))
        assertEquals(12, logged.int("max_fps"))

        // ...and not applied: the pipeline gets the whole ladder uncapped.
        val config = checkNotNull(sim.pipeline.config)
        assertEquals(0, config.bestRung)
        assertNull(config.maxFps)
        assertEquals(ProbeSchedule.LADDER, config.ladder)
        assertEquals(0, config.startRung)

        // One run and one run_end per run, in order, at the ladder's values.
        val runs = lines.filter { it.kind == "run" }
        assertEquals((0..5).toList(), runs.map { it.int("run") })
        assertEquals((0..5).toList(), lines.filter { it.kind == "run_end" }.map { it.int("run") })
        assertEquals(listOf(0, 1, 2, 3, 4, 2), runs.map { it.int("rung") })
        assertEquals(List(5) { "constant" } + "step", runs.map { it.str("mode") })
        assertEquals(listOf(20, 20, 15, 12, 10, 15), runs.map { it.int("fps") })
        assertEquals(listOf(900, 600, 400, 250, 150, 400), runs.map { it.int("kbps") })
        assertEquals(
            listOf(480, 480, 480, 336, 288, 480) to listOf(640, 640, 640, 448, 384, 640),
            runs.map { it.int("w") } to runs.map { it.int("h") },
        )
        for (run in runs) {
            assertEquals(2000, run.int("gop_ms"))
            assertEquals("A", run.str("path"))
            assertEquals(emptyList<String>(), run.getValue("dropped").jsonArray.map { it.str() })
        }
        val end = lines.last()
        assertEquals("end", end.kind)
        assertEquals(6, end.int("runs"))
        assertEquals("done", end.str("reason"))

        // Every run on an encoder of its own, of its size; the same size reached by way of rung 4.
        assertEquals(
            listOf(480 to 640, 480 to 640, 480 to 640, 336 to 448, 288 to 384, 480 to 640),
            sim.pipeline.started.map { it.width to it.height },
        )
        assertEquals(6, sim.pipeline.submittedTo.size)
        val rungCalls = sim.pipeline.calls.map { it.second }.filter { it.startsWith("setRung") }
        assertEquals(
            listOf(
                "setRung 4",
                "setRung 1",
                "setRung 4",
                "setRung 2",
                "setRung 3",
                "setRung 4",
                "setRung 2",
            ),
            rungCalls,
        )

        // Each run lasts its duration from its first IDR; the paced rate is the rung's.
        val ends =
            sim.pipeline.calls.filter { it.second.startsWith("setRung") || it.second == "stop" }
        val durations = listOf(20_000L, 20_000L, 60_000L, 20_000L, 20_000L, 18_000L)
        for ((index, run) in runs.withIndex()) {
            val outs = lines.filter { it.kind == "out" && it.int("run") == index }
            val firstIdr = outs.first { it.bool("key") }.long("t_us")
            val endUs = firstIdr + durations[index] * 1000
            val call = ends.first { (at, _) -> (at - 5 * SECOND) / 1000 >= endUs }
            assertEquals("run $index ends at its duration", endUs, (call.first - 5 * SECOND) / 1000)
            val ins = lines.filter { it.kind == "in" && it.int("run") == index }
            val second = ins.filter { it.long("pts_us") in 5_000_000 until 6_000_000 }
            assertTrue(
                "run $index: ${second.size} frames a second at ${run.int("fps")} fps",
                second.size in run.int("fps") - 1..run.int("fps") + 1,
            )
        }

        // A keyframe request a second after every IDR that answered none, in constant runs only.
        for (index in 0..5) {
            val runLines = lines.filter {
                it.kind in setOf("out", "kf_req") && it.int("run") == index
            }
            var pending = false
            val expected = ArrayList<Long>()
            for (line in runLines) {
                if (line.kind == "kf_req") pending = true
                else if (line.bool("key")) {
                    if (!pending) expected += line.long("t_us") + 1_000_000
                    pending = false
                }
            }
            val requests = runLines.filter { it.kind == "kf_req" }.map { it.long("t_us") - 1000 }
            if (index == 5) {
                assertEquals(emptyList<Long>(), requests)
            } else {
                assertTrue("run $index: requests $requests", requests.isNotEmpty())
                assertEquals(expected.take(requests.size), requests)
                assertTrue(requests.all { it in expected })
            }
        }

        // The step run steps its bitrate at 6 s and 12 s from its first IDR.
        val step = lines.filter { it.kind in setOf("out", "rate") && it.int("run") == 5 }
        val stepIdr = step.first { it.kind == "out" && it.bool("key") }.long("t_us")
        val rates = step.filter { it.kind == "rate" }
        assertEquals(listOf(200, 400), rates.map { it.int("kbps") })
        assertEquals(
            listOf(stepIdr + 6_001_000, stepIdr + 12_001_000),
            rates.map { it.long("t_us") },
        )
    }

    private fun JsonElement.str(): String = (this as JsonPrimitive).content
}
