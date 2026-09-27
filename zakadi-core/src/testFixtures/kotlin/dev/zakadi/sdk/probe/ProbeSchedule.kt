@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.Rung

/** The `mode` of a run. */
enum class RunMode(val wire: String) {
    /** One rung's size, rate and bitrate throughout. */
    CONSTANT("constant"),

    /** Bitrate steps at one size and rate. */
    STEP("step"),
}

/** A live bitrate request, [atMs] after the run's first IDR. */
data class RateStep(val atMs: Long, val kbps: Int)

/** One run of a schedule, on an encoder of its own. */
data class ScheduledRun(
    val mode: RunMode,
    val rung: Rung,
    /** Measured from the run's first IDR. */
    val durationMs: Long,
    /** A keyframe request [ProbeSchedule.keyframeDelayMs] after every IDR that answered none. */
    val keyframeRequests: Boolean,
    val rateSteps: List<RateStep> = emptyList(),
)

/**
 * A probe schedule: the runs a probe makes over one [ladder], each on a new encoder and its surface
 * while the camera stays bound, as a rung change does.
 */
data class ProbeSchedule(
    /** The `schedule` of the `device` line. */
    val number: Int,
    val ladder: List<Rung>,
    val runs: List<ScheduledRun>,
    /** `gop_ms` of spec 01 section 1.5. */
    val gopMs: Int,
    val keyframeDelayMs: Long,
    /** 1 as specified; a shortened schedule divides every duration and step time by it. */
    val divisor: Int = 1,
) {
    /** The same runs with every duration and step time divided by [by]; GOP and delay kept. */
    fun shortened(by: Int): ProbeSchedule {
        require(by >= 1) { "divisor $by is below 1" }
        return copy(
            divisor = divisor * by,
            runs =
                runs.map { run ->
                    run.copy(
                        durationMs = run.durationMs / by,
                        rateSteps = run.rateSteps.map { it.copy(atMs = it.atMs / by) },
                    )
                },
        )
    }

    companion object {
        /** `ready.ladder` of spec 01 section 1.5. */
        val LADDER: List<Rung> =
            listOf(
                Rung(0, 480, 640, 20, 900),
                Rung(1, 480, 640, 20, 600),
                Rung(2, 480, 640, 15, 400),
                Rung(3, 336, 448, 12, 250),
                Rung(4, 288, 384, 10, 150),
            )

        /**
         * Schedule 1: runs 0 to 4 constant at rungs 0 to 4 of the ladder, each 20 s from its first
         * IDR and rung 2 for 60 s (spec 10 section 10.3), `gop_ms` 2000, a keyframe request 1000 ms
         * after every IDR that answered no request; run 5 stepping at rung 2's size and rate for 18
         * s at 400 kbps, 200 kbps from 6 s and 400 kbps from 12 s, without keyframe requests.
         */
        val ONE: ProbeSchedule =
            ProbeSchedule(
                number = 1,
                ladder = LADDER,
                runs =
                    LADDER.map {
                        ScheduledRun(
                            RunMode.CONSTANT,
                            it,
                            if (it.index == 2) 60_000 else 20_000,
                            keyframeRequests = true,
                        )
                    } +
                        ScheduledRun(
                            RunMode.STEP,
                            LADDER[2],
                            18_000,
                            keyframeRequests = false,
                            rateSteps = listOf(RateStep(6_000, 200), RateStep(12_000, 400)),
                        ),
                gopMs = 2000,
                keyframeDelayMs = 1000,
            )
    }
}

/**
 * What one run of a schedule does next, from the times of its IDRs alone, on the `t_us` clock in
 * nanoseconds: nothing before its first IDR; then the end once its duration has passed, else a
 * keyframe request due and the bitrate steps reached. An IDR answers every request before it; one
 * that answers none schedules the next request.
 */
class RunControl(val run: ScheduledRun, keyframeDelayMs: Long) {
    /** What the run asks of the pipeline. */
    sealed interface Action {
        data object RequestKeyframe : Action

        data class SetBitrate(val kbps: Int) : Action

        data object End : Action
    }

    private val delayNanos = keyframeDelayMs * NANOS_PER_MS
    private var keyframeDue: Long? = null
    private var outstanding = 0
    private var steps = 0

    /** The time of the run's first IDR. */
    var firstIdrNanos: Long? = null
        private set

    /** Whether the run has ended, by its duration or by [end]. */
    var ended = false
        private set

    /** An output holding an IDR at [atNanos]. */
    fun onIdr(atNanos: Long) {
        if (firstIdrNanos == null) firstIdrNanos = atNanos
        if (outstanding > 0) {
            outstanding = 0
        } else if (run.keyframeRequests) {
            keyframeDue = atNanos + delayNanos
        }
    }

    /** Ends the run whatever its schedule. */
    fun end() {
        ended = true
    }

    /** When the next action falls due, or null before the first IDR and after the end. */
    fun nextDue(): Long? {
        if (ended) return null
        val first = firstIdrNanos ?: return null
        val step = run.rateSteps.getOrNull(steps)?.let { first + it.atMs * NANOS_PER_MS }
        return listOfNotNull(first + run.durationMs * NANOS_PER_MS, keyframeDue, step).min()
    }

    /** The actions due at [nowNanos], in order; after an end, none. */
    fun due(nowNanos: Long): List<Action> {
        if (ended) return emptyList()
        val first = firstIdrNanos ?: return emptyList()
        if (nowNanos - first >= run.durationMs * NANOS_PER_MS) {
            ended = true
            return listOf(Action.End)
        }
        val actions = ArrayList<Action>()
        val due = keyframeDue
        if (due != null && nowNanos >= due) {
            keyframeDue = null
            outstanding++
            actions += Action.RequestKeyframe
        }
        while (
            steps < run.rateSteps.size &&
                nowNanos - first >= run.rateSteps[steps].atMs * NANOS_PER_MS
        ) {
            actions += Action.SetBitrate(run.rateSteps[steps].kbps)
            steps++
        }
        return actions
    }

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
    }
}
