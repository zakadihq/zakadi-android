@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CameraClockDomain
import dev.zakadi.sdk.capture.CameraEvent
import dev.zakadi.sdk.capture.CaptureConfig
import dev.zakadi.sdk.capture.CaptureError
import dev.zakadi.sdk.capture.CaptureListener
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.ClockEvent
import dev.zakadi.sdk.capture.ExposureEvent
import dev.zakadi.sdk.capture.FrameEvent
import dev.zakadi.sdk.capture.PathFallback
import dev.zakadi.sdk.capture.Rung
import dev.zakadi.sdk.capture.RungEvent
import dev.zakadi.sdk.device.DeviceCaps
import dev.zakadi.sdk.encode.AnnexB
import dev.zakadi.sdk.encode.BitrateEvent
import dev.zakadi.sdk.encode.EncoderError
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.FORMAT_REFUSAL_STEPS
import dev.zakadi.sdk.encode.FormatEvent
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.ParameterSetsChanged
import dev.zakadi.sdk.encode.avcCodecString

/** The capture pipeline as the probe drives it; every call returns at once. */
interface ProbeTarget {
    fun setRung(index: Int)

    fun requestKeyframe()

    fun requestBitrate(kbps: Int)

    /** Stops the pipeline; [EncoderProbe.onStopped] is due once it has stopped. */
    fun stop()
}

/** What the probe reads of the device while it runs; each null where it cannot. */
interface ProbeReadings {
    /** 7.7's name for the thermal state. */
    fun thermal(): String?

    fun batteryPercent(): Int?

    /** The process CPU time in milliseconds. */
    fun cpuTimeMs(): Long?

    /** The frame rate range the pipeline asks the camera for on [path]. */
    fun cameraFps(path: CapturePath): IntRange?
}

/**
 * The encoder probe of phase 0 measurement 6 (spec 09 section 9.11 item 6, D105): it runs a
 * [ProbeSchedule] through the capture pipeline and writes what the pipeline's listener reports as
 * log format 1 ([LogLines]), one line at a time through [write].
 *
 * Run 0 starts the pipeline ([captureConfig]); every later run re-creates the encoder and its
 * surface through a rung change, by way of a rung of another size when the two runs share one, so
 * the camera stays bound. A run takes the first encoder of its size that frames reach; its `run`
 * line comes with that first frame, and its `run_end` once that encoder has stopped and drained.
 * The tier's and quirks' [caps] are logged in `args` and never applied: the pipeline gets the whole
 * ladder at the ladder's rates. A run that has not ended 10 s after its duration ends with the
 * error `timeout`; a capture failure ends the probe with `error`, and no encoder of the preference
 * with `unsupported_device`.
 *
 * Not thread-safe: the listener calls, [start], [advance] and [onStopped] come from one thread, and
 * [advance] is due whenever [nextDue] passes. Times are `SystemClock.elapsedRealtimeNanos()`.
 */
class EncoderProbe(
    private val schedule: ProbeSchedule,
    private val device: ProbeDevice,
    private val args: ProbeArgs,
    private val caps: DeviceCaps?,
    private val target: ProbeTarget,
    private val readings: ProbeReadings,
    private val write: (String) -> Unit,
) : CaptureListener {
    private var origin = 0L
    private var now = 0L
    private var current: RunState? = null
    private val bound = HashMap<Int, RunState>()
    private var lastBound = Int.MIN_VALUE
    private val encoders = HashMap<Int, EncoderStarted>()
    private val formats = HashMap<Int, MutableList<FormatEvent>>()
    private val parameterSets = HashMap<Int, Pair<ByteArray?, ByteArray?>>()
    private val held = HashMap<Int, MutableList<(RunState) -> Unit>>()
    private var pendingSets: ParameterSetsChanged? = null
    private var camera: CameraEvent? = null
    private var clockDomain: CameraClockDomain? = null
    private var ev: Double? = null
    private var path = args.path
    private var completed = 0
    private var reason: EndReason? = null
    private var stopping = false

    /** Whether the `end` line is written. */
    var finished = false
        private set

    private inner class RunState(val index: Int, val plan: ScheduledRun, val startedAt: Long) {
        val control = RunControl(plan, schedule.keyframeDelayMs)
        var width = plan.rung.width
        var height = plan.rung.height
        var capped = false
        var encoderId: Int? = null
        var originUs = 0L
        var lineAt: Long? = null
        var ending = false
        var done = false
        var error: String? = null
        val records = ArrayList<ProbeRecord>()
        var inputs = 0
        var outputs = 0
        var idr = 0
        var idrBare = 0
        var requests = 0
        var drops = 0L
        var nextTick = 0L
        var windowStart = 0L
        var captured = 0
        var submitted = 0
        var encoded = 0
        var bits = 0L
        var cpuMark: Long? = null
    }

    /**
     * How the pipeline starts: the schedule's ladder at run 0's rung, with the encoder and path of
     * [args], no best rung or frame rate cap, and the schedule's GOP.
     */
    fun captureConfig(): CaptureConfig =
        CaptureConfig(
            ladder = schedule.ladder,
            startRung = schedule.runs.first().rung.index,
            encoder = args.encoder,
            path = args.path,
            bestRung = 0,
            maxFps = null,
            gopMs = schedule.gopMs,
        )

    /** Writes the `device` line at [nowNanos], the origin of `t_us`, and begins run 0. */
    fun start(nowNanos: Long) {
        check(current == null && !stopping) { "a probe starts once" }
        origin = nowNanos
        now = nowNanos
        write(LogLines.device(device, schedule.number, args.json(caps)))
        current = RunState(0, schedule.runs.first(), nowNanos)
    }

    /** Ends the probe as `unsupported_device` before any run, as without a front camera. */
    fun unsupported() {
        endProbe(EndReason.UNSUPPORTED_DEVICE)
    }

    /** Acts on everything due by [nowNanos]: ticks, keyframe requests, bitrate steps, ends. */
    fun advance(nowNanos: Long) {
        now = maxOf(now, nowNanos)
        act()
    }

    /** When [advance] is next due, or null when nothing waits on time. */
    fun nextDue(): Long? {
        val run = current ?: return null
        if (stopping || run.ending) return null
        val tick = if (run.lineAt != null) run.nextTick else null
        val timeout = run.startedAt + (run.plan.durationMs + TIMEOUT_MS) * NANOS_PER_MS
        return listOfNotNull(tick, run.control.nextDue(), timeout).min()
    }

    /** The pipeline stopped at [nowNanos]: the runs still open end, then the `end` line. */
    fun onStopped(nowNanos: Long) {
        if (finished) return
        now = maxOf(now, nowNanos)
        if (reason == null) reason = EndReason.ERROR
        for (run in bound.values.sortedBy { it.index }) finishRun(run, now)
        val last = current
        if (last != null && !last.done) {
            val skip = reason == EndReason.UNSUPPORTED_DEVICE && last.encoderId == null
            if (!skip) finishRun(last, now)
        }
        write(
            LogLines.end(
                us(now),
                completed,
                checkNotNull(reason),
                readings.thermal(),
                readings.batteryPercent(),
            )
        )
        finished = true
    }

    override fun onRung(event: RungEvent) {
        val run = current ?: return
        if (run.encoderId != null || run.ending || event.requested != run.plan.rung.index) return
        run.width = event.width
        run.height = event.height
        run.capped = event.applied != event.requested
        if (run.capped)
            run.error = run.error ?: "rung ${event.requested} applied as ${event.applied}"
    }

    override fun onEncoderStarted(event: EncoderStarted) {
        encoders[event.encoderId] = event
    }

    override fun onFormat(event: FormatEvent) {
        formats.getOrPut(event.encoderId) { ArrayList() } += event
    }

    override fun onEncoderStopped(event: EncoderStopped) {
        now = maxOf(now, event.atNanos)
        val id = event.encoderId
        held.remove(id)
        encoders.remove(id)
        formats.remove(id)
        parameterSets.remove(id)
        if (pendingSets?.encoderId == id) pendingSets = null
        val run = bound[id] ?: return
        if (!run.ending) {
            run.error = run.error ?: "encoder stopped"
            run.ending = true
            run.control.end()
            finishRun(run, event.atNanos)
            next(run)
        } else {
            finishRun(run, event.atNanos)
        }
    }

    override fun onFrame(event: FrameEvent) {
        now = maxOf(now, event.atNanos)
        val run = current
        val live = run != null && run.lineAt != null && !run.ending
        when (event.kind) {
            FrameEvent.Kind.CAPTURED -> if (live) checkNotNull(run).captured++
            FrameEvent.Kind.DROPPED -> if (live) checkNotNull(run).drops++
            FrameEvent.Kind.SUBMITTED -> submitted(event)
        }
        act()
    }

    private fun submitted(event: FrameEvent) {
        val id = event.encoderId ?: return
        val run = bound[id] ?: bind(id, event) ?: return
        val tUs = us(event.atNanos)
        val ptsUs = event.timestampNanos / 1000 - run.originUs
        write(LogLines.input(tUs, run.index, ptsUs))
        run.records += ProbeRecord.input(tUs, ptsUs)
        run.inputs++
        if (!run.ending) run.submitted++
    }

    /** Binds encoder [id] to the current run at its first frame [first], when it is the run's. */
    private fun bind(id: Int, first: FrameEvent): RunState? {
        val run = current ?: return null
        if (run.encoderId != null || run.ending || stopping || id <= lastBound) return null
        val started = encoders[id] ?: return null
        if (started.width != run.width || started.height != run.height) return null
        run.encoderId = id
        bound[id] = run
        lastBound = id
        run.originUs = first.timestampNanos / 1000
        run.lineAt = first.atNanos
        run.windowStart = first.atNanos
        run.captured = 1
        run.nextTick = first.atNanos + TICK_MS * NANOS_PER_MS
        run.cpuMark = readings.cpuTimeMs()
        writeRunLine(run, first.atNanos, first.timestampNanos)
        held.remove(id)?.forEach { it(run) }
        return run
    }

    override fun onOutputBuffer(event: OutputBuffer) {
        now = maxOf(now, event.atNanos)
        val id = event.encoderId
        val sets = pendingSets
        if (sets != null && sets.encoderId == id) {
            pendingSets = null
            val source = if (event.codecConfig || event.accessUnit == null) "csd" else "inband"
            forEncoder(id) { run -> writeParams(run, sets, source) }
        }
        val au = event.accessUnit
        val run = bound[id]
        if (au != null && run != null) output(run, event, au)
        act()
    }

    private fun output(run: RunState, event: OutputBuffer, au: ByteArray) {
        val tUs = us(event.atNanos)
        val ptsUs = event.presentationTimeUs - run.originUs
        val types = event.nalTypes
        val key = AnnexB.NAL_IDR in types
        val withSets = AnnexB.NAL_SPS in types && AnnexB.NAL_PPS in types
        val known = parameterSets[event.encoderId]
        val sc3 = threeByteStartCodes(event, known?.first, known?.second)
        write(
            LogLines.output(
                tUs,
                OutLine(run.index, ptsUs, event.size, key, event.keyFlag, withSets, sc3, types),
            )
        )
        run.records += ProbeRecord.output(tUs, ptsUs, event.size, key)
        run.outputs++
        if (key) {
            run.idr++
            if (!withSets) run.idrBare++
        }
        if (!run.ending) {
            run.encoded++
            run.bits += au.size * 8L
            if (key) run.control.onIdr(event.atNanos)
        }
    }

    override fun onParameterSets(event: ParameterSetsChanged) {
        parameterSets[event.encoderId] = event.sps to event.pps
        if (event.fromFormat) {
            forEncoder(event.encoderId) { run -> writeParams(run, event, "format") }
        } else {
            pendingSets = event
        }
    }

    private fun writeParams(run: RunState, event: ParameterSetsChanged, source: String) {
        val codec = event.sps?.let(::avcCodecString)
        write(LogLines.params(us(event.atNanos), run.index, source, event.sps, event.pps, codec))
    }

    override fun onKeyframe(event: KeyframeEvent) {
        val repeat =
            when (event.kind) {
                KeyframeEvent.Kind.REQUESTED -> false
                KeyframeEvent.Kind.REPEATED -> true
                KeyframeEvent.Kind.ANSWERED -> return
            }
        forEncoder(event.encoderId) { run ->
            if (!repeat) run.requests++
            val tUs = us(event.atNanos)
            write(LogLines.keyframeRequest(tUs, run.index, maxOf(run.requests, 1), repeat))
            run.records += ProbeRecord.keyframeRequest(tUs, repeat)
        }
    }

    override fun onBitrate(event: BitrateEvent) {
        forEncoder(event.encoderId) { run ->
            write(LogLines.rate(us(event.atNanos), run.index, event.requestedBps / 1000))
        }
    }

    override fun onEncoderError(event: EncoderError) {
        forEncoder(event.encoderId) { run -> run.error = run.error ?: brief(event.message) }
    }

    override fun onCamera(event: CameraEvent) {
        camera = event
        path = event.path
    }

    override fun onClock(event: ClockEvent) {
        clockDomain = event.domain
    }

    override fun onExposure(event: ExposureEvent) {
        ev = event.ev
    }

    override fun onPathFallback(event: PathFallback) {
        path = CapturePath.B
        val run = current ?: return
        if (run.encoderId != null && !run.ending) {
            run.error = run.error ?: brief("fallback: ${event.reason}")
            endProbe(EndReason.ERROR)
        }
    }

    override fun onError(event: CaptureError) {
        if (stopping) return
        if (event.kind == CaptureError.Kind.NO_ENCODER) {
            endProbe(EndReason.UNSUPPORTED_DEVICE)
            return
        }
        current?.let { it.error = it.error ?: brief("${event.kind}: ${event.message}") }
        endProbe(EndReason.ERROR)
    }

    /** Runs [action] for the run of encoder [id] now, or once a frame binds it to a run. */
    private fun forEncoder(id: Int, action: (RunState) -> Unit) {
        val run = bound[id]
        if (run != null) action(run) else held.getOrPut(id) { ArrayList() } += action
    }

    private fun act() {
        val run = current ?: return
        if (stopping || run.ending) return
        if (run.lineAt != null && now >= run.nextTick) {
            tick(run)
            while (run.nextTick <= now) run.nextTick += TICK_MS * NANOS_PER_MS
        }
        for (action in run.control.due(now)) {
            when (action) {
                RunControl.Action.End -> {
                    endRun(run)
                    return
                }
                RunControl.Action.RequestKeyframe -> target.requestKeyframe()
                is RunControl.Action.SetBitrate -> target.requestBitrate(action.kbps)
            }
        }
        if (now >= run.startedAt + (run.plan.durationMs + TIMEOUT_MS) * NANOS_PER_MS) {
            run.error = run.error ?: "timeout"
            run.control.end()
            endRun(run)
        }
    }

    private fun tick(run: RunState) {
        val windowMs = (now - run.windowStart) / 1_000_000.0
        val cpu = readings.cpuTimeMs()
        val mark = run.cpuMark
        val counts =
            TickCounts(
                captured = run.captured,
                submitted = run.submitted,
                encoded = run.encoded,
                preEncodeDrops = run.drops,
                encQueue = (run.inputs - run.outputs).toLong(),
                encodedKbps = if (windowMs > 0) run.bits / windowMs else 0.0,
                thermal = readings.thermal(),
                cpuMs = if (cpu != null && mark != null) cpu - mark else null,
            )
        write(LogLines.tick(us(now), run.index, counts))
        run.captured = 0
        run.submitted = 0
        run.encoded = 0
        run.bits = 0
        run.windowStart = now
        run.cpuMark = cpu
    }

    /** The schedule ended [run]: the next run begins, or the probe stops after the last. */
    private fun endRun(run: RunState) {
        run.ending = true
        if (run.encoderId == null) finishRun(run, now)
        next(run)
    }

    private fun next(run: RunState) {
        if (stopping) return
        val index = run.index + 1
        if (index >= schedule.runs.size) {
            endProbe(EndReason.DONE)
            return
        }
        val plan = schedule.runs[index]
        current = RunState(index, plan, now)
        val size = plan.rung.width to plan.rung.height
        if (run.capped || size == (run.width to run.height)) {
            detour(schedule.ladder, run.width to run.height, size)?.let { target.setRung(it) }
        }
        target.setRung(plan.rung.index)
    }

    private fun endProbe(why: EndReason) {
        if (stopping) return
        reason = why
        stopping = true
        current?.let {
            it.ending = true
            it.control.end()
        }
        target.stop()
    }

    private fun finishRun(run: RunState, atNanos: Long) {
        if (run.done) return
        run.done = true
        run.encoderId?.let { bound.remove(it) }
        if (run.lineAt == null) writeRunLine(run, atNanos, null)
        val totals =
            RunTotals(
                inputs = run.inputs,
                outputs = run.outputs,
                idr = run.idr,
                idrBare = run.idrBare,
                summary = ProbeSummary.of(run.records),
                error = run.error,
            )
        write(LogLines.runEnd(us(atNanos), run.index, totals))
        completed++
    }

    private fun writeRunLine(run: RunState, atNanos: Long, firstFrameNanos: Long?) {
        val id = run.encoderId
        val started = id?.let { encoders[it] }
        val events = id?.let { formats[it] }.orEmpty()
        val asked = events.firstOrNull {
            it.kind == FormatEvent.Kind.ASKED && it.step == started?.step
        }
        val readBack = events.firstOrNull {
            it.kind == FormatEvent.Kind.OUTPUT_READ_BACK && it.step == started?.step
        }
        val rung = run.plan.rung
        val fps = readings.cameraFps(path)
        val cam = camera
        write(
            LogLines.run(
                us(atNanos),
                RunLine(
                    run = run.index,
                    mode = run.plan.mode.wire,
                    rung = rung.index,
                    w = rung.width,
                    h = rung.height,
                    fps = rung.fps,
                    kbps = rung.videoKbps,
                    gopMs = schedule.gopMs,
                    path = path.name,
                    encoder = started?.codecName,
                    hw = started?.hardware,
                    profile = asked?.values?.get(KEY_PROFILE)?.toIntOrNull()?.let(::avcProfileName),
                    level = asked?.values?.get(KEY_LEVEL)?.toIntOrNull()?.let(::avcLevelName),
                    bitrateMode =
                        asked?.values?.get(KEY_BITRATE_MODE)?.toIntOrNull()?.let(::bitrateModeName),
                    dropped = started?.let { FORMAT_REFUSAL_STEPS[it.step].toList() },
                    outFormat = readBack?.values,
                    camera =
                        cam?.let {
                            CameraLine(
                                w = it.width,
                                h = it.height,
                                rotation = it.rotationDegrees,
                                fpsMin = fps?.first,
                                fpsMax = fps?.last,
                                ev = ev,
                                clock = clockDomain?.name?.lowercase(),
                            )
                        },
                    t0Us =
                        if (clockDomain == CameraClockDomain.BOOTTIME && firstFrameNanos != null)
                            us(firstFrameNanos)
                        else null,
                    thermal = readings.thermal(),
                ),
            )
        )
    }

    private fun us(atNanos: Long): Long = (atNanos - origin) / 1000

    private companion object {
        const val NANOS_PER_MS = 1_000_000L
        const val TICK_MS = 1000L
        const val TIMEOUT_MS = 10_000L
        const val KEY_PROFILE = "profile"
        const val KEY_LEVEL = "level"
        const val KEY_BITRATE_MODE = "bitrate-mode"
        const val BRIEF = 120

        fun brief(text: String): String = if (text.length <= BRIEF) text else text.take(BRIEF)
    }
}

/**
 * A rung of [ladder] whose size differs from both [from] and [to], the smallest: a rung change
 * through it re-creates the encoder even when [from] and [to] share a size. Null when none does.
 */
fun detour(ladder: List<Rung>, from: Pair<Int, Int>, to: Pair<Int, Int>): Int? =
    ladder
        .filter { (it.width to it.height) != from && (it.width to it.height) != to }
        .minByOrNull { it.width * it.height }
        ?.index

/**
 * The 3-byte start codes of an output buffer as the encoder wrote it (spec 07 section 7.19), told
 * from what the listener reports: the buffer's size and NAL types, and its access unit, whose start
 * codes all have 4 bytes and which carries [sps] and [pps] (the encoder's latest) in front of an
 * IDR that lacked them. Exact when every start code of the buffer has 3 or 4 bytes and no other
 * zero byte stands before, between or after its NAL units; null when the counts cannot hold, as
 * such bytes or a repeated parameter set make them.
 */
fun threeByteStartCodes(buffer: OutputBuffer, sps: ByteArray?, pps: ByteArray?): Int? {
    val au = buffer.accessUnit ?: return null
    val types = buffer.nalTypes
    val n = types.size
    val spsCount = types.count { it == AnnexB.NAL_SPS }
    val ppsCount = types.count { it == AnnexB.NAL_PPS }
    val prepended =
        AnnexB.NAL_IDR in types && (spsCount == 0 || ppsCount == 0) && sps != null && pps != null
    val nalBytes =
        if (prepended) {
            if (spsCount > 1 || ppsCount > 1) return null
            val units = n - spsCount - ppsCount + 2
            val inBuffer =
                (if (spsCount == 1) sps.size else 0) + (if (ppsCount == 1) pps.size else 0)
            au.size - 4 * units - sps.size - pps.size + inBuffer
        } else {
            au.size - 4 * n
        }
    val count = 4 * n + nalBytes - buffer.size
    return if (count in 0..n) count else null
}
