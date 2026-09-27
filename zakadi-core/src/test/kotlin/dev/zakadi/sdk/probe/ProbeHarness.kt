@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CameraClockDomain
import dev.zakadi.sdk.capture.CameraEvent
import dev.zakadi.sdk.capture.CaptureConfig
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.ClockEvent
import dev.zakadi.sdk.capture.DropReason
import dev.zakadi.sdk.capture.ExposureEvent
import dev.zakadi.sdk.capture.FrameEvent
import dev.zakadi.sdk.capture.PaceDecision
import dev.zakadi.sdk.capture.Pacer
import dev.zakadi.sdk.capture.Rung
import dev.zakadi.sdk.capture.RungEvent
import dev.zakadi.sdk.device.CapabilityProbe
import dev.zakadi.sdk.device.CheckOutcome
import dev.zakadi.sdk.device.DeviceCaps
import dev.zakadi.sdk.device.DeviceFacts
import dev.zakadi.sdk.device.ProbeCheck
import dev.zakadi.sdk.device.ProbeReport
import dev.zakadi.sdk.encode.BitrateEvent
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.FormatEvent
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.PPS
import dev.zakadi.sdk.encode.ParameterSetsChanged
import dev.zakadi.sdk.encode.SPS
import dev.zakadi.sdk.encode.stream4
import java.util.PriorityQueue
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.boolean
import kotlinx.serialization.json.booleanOrNull
import kotlinx.serialization.json.double
import kotlinx.serialization.json.doubleOrNull
import kotlinx.serialization.json.int
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlinx.serialization.json.long

const val MS: Long = 1_000_000L
const val SECOND: Long = 1000 * MS

/** The lines of a whole log, read back and checked by [LogReader]. */
fun parseLog(lines: List<String>): List<JsonObject> =
    LogReader.read(lines.joinToString("\n", postfix = "\n"))

val JsonObject.kind: String
    get() = str("kind")!!

fun JsonObject.str(key: String): String? =
    (getValue(key) as? JsonPrimitive)?.takeIf { it.isString }?.content

fun JsonObject.long(key: String): Long = getValue(key).jsonPrimitive.long

fun JsonObject.int(key: String): Int = getValue(key).jsonPrimitive.int

fun JsonObject.intOrNull(key: String): Int? = getValue(key).jsonPrimitive.intOrNull

fun JsonObject.double(key: String): Double = getValue(key).jsonPrimitive.double

fun JsonObject.doubleOrNull(key: String): Double? = getValue(key).jsonPrimitive.doubleOrNull

fun JsonObject.bool(key: String): Boolean = getValue(key).jsonPrimitive.boolean

fun JsonObject.boolOrNull(key: String): Boolean? = getValue(key).jsonPrimitive.booleanOrNull

fun JsonObject.isNull(key: String): Boolean = getValue(key) is JsonNull

fun JsonElement.obj(): JsonObject = jsonObject

/** Readings that never change, with a CPU clock that counts 40 ms a call. */
class FakeReadings : ProbeReadings {
    private var cpu = 0L

    override fun thermal(): String = "nominal"

    override fun batteryPercent(): Int = 80

    override fun cpuTimeMs(): Long {
        cpu += 40
        return cpu
    }

    override fun cameraFps(path: CapturePath): IntRange = 15..30
}

/** A capability probe report of a phone on tier S, with each check of spec 07 section 7.26. */
fun report(
    lowRam: Boolean = false,
    totalMem: Long = 3L shl 30,
    encoderPassed: Boolean = true,
): ProbeReport =
    ProbeReport(
        fingerprint = "zakadi/test/fake:14/UP1A/1:user/release-keys",
        sdkInt = 34,
        probeVersion = CapabilityProbe.VERSION,
        checks =
            listOf(
                ProbeCheck(
                    CapabilityProbe.AVC_ENCODER,
                    if (encoderPassed) CheckOutcome.PASSED else CheckOutcome.FAILED,
                    "c2.fake.avc.encoder step 0",
                    if (encoderPassed) 87_500_000L else null,
                ),
                ProbeCheck(CapabilityProbe.CBR, CheckOutcome.PASSED, "CBR listed"),
                ProbeCheck(CapabilityProbe.CONSTRAINED_BASELINE, CheckOutcome.PASSED, "listed"),
                ProbeCheck(CapabilityProbe.OPUS, CheckOutcome.NOT_RUN, "waits for 7.20"),
                ProbeCheck(CapabilityProbe.LOW_RAM, CheckOutcome.PASSED, "isLowRamDevice false"),
                ProbeCheck(CapabilityProbe.TOTAL_MEM, CheckOutcome.PASSED, "$totalMem bytes"),
                ProbeCheck(
                    CapabilityProbe.CAMERA_LEVEL,
                    CheckOutcome.PASSED,
                    "hardware level LIMITED",
                ),
                ProbeCheck(CapabilityProbe.EGL_RECORDABLE, CheckOutcome.PASSED, "config created"),
                ProbeCheck(CapabilityProbe.FRONT_CAMERA, CheckOutcome.PASSED, "present"),
            ),
        facts =
            DeviceFacts(
                frontCamera = true,
                avcEncoder = encoderPassed,
                lowRamDevice = lowRam,
                totalMemBytes = totalMem,
                legacyCamera = false,
                eglRecordable = true,
            ),
    )

/** One AVC encoder as the `device` line lists it. */
val FAKE_ENCODER =
    ProbeEncoder(
        name = "c2.fake.avc.encoder",
        hw = true,
        vendor = true,
        alias = false,
        cbr = true,
        cb = true,
        maxLevel = "4.1",
        rungs = listOf(0, 1, 2, 3, 4),
        kbpsRange = listOf(1.0, 20_000.0),
        achievableFps = listOf(24.0, 120.0),
    )

/** The `device` facts of [report] on a Tecno phone. */
fun device(report: ProbeReport = report()): ProbeDevice =
    ProbeDevice.of(
        report,
        wallMs = 1_790_000_000_000,
        phone = "TECNO TECNO KI5k",
        soc = "Mediatek MT6769",
        os = "14",
        osBuild = report.fingerprint,
        encoders = listOf(FAKE_ENCODER),
        thermal = "nominal",
        batteryPct = 81,
        charging = false,
    )

/** What a slice of a frame carries in these fixtures: never to be found in a log. */
val FRAME_MARKER = "ZAKADI-FRAME-PAYLOAD".encodeToByteArray()

/**
 * A capture pipeline simulated on a fake clock in nanoseconds, as the probe drives it: a 30 fps
 * front camera, the pacer of spec 07 section 7.18, one encoder at a time re-created on a size
 * change (the requests of one instant coalesced, as the pipeline's encoder thread does), outputs
 * [encodeDelayNanos] after each frame with an IDR every `gop_ms` or after a keyframe request, and
 * stops that drain the encoder. [run] delivers each event and each due [EncoderProbe.advance].
 */
class FakePipeline(
    private val cameraFps: Int = 30,
    private val encodeDelayNanos: Long = 20 * MS,
    private val cameraDelivers: Boolean = true,
) : ProbeTarget {
    lateinit var probe: EncoderProbe
    var now = 0L
        private set

    /** Each call of the probe with its time: `setRung 4`, `keyframe`, `bitrate 200`, `stop`. */
    val calls = ArrayList<Pair<Long, String>>()

    /** Each encoder started: its id, width and height. */
    val started = ArrayList<EncoderStarted>()

    /** The encoder each frame was submitted to, by encoder id. */
    val submittedTo = LinkedHashMap<Int, Int>()

    var config: CaptureConfig? = null
        private set

    private class Scheduled(val at: Long, val seq: Int, val action: () -> Unit)

    private class Encoder(val id: Int, val rung: Rung, var kbps: Int) {
        var outputs = 0
        var lastIdr = Long.MIN_VALUE / 2
        var requestedAt: Long? = null
        var pending = 0
        var stopping = false
    }

    private val queue = PriorityQueue<Scheduled>(compareBy<Scheduled>({ it.at }, { it.seq }))
    private var seq = 0
    private lateinit var rung: Rung
    private lateinit var pacer: Pacer
    private var attached: Encoder? = null
    private var live: Encoder? = null
    private var wanted: Rung? = null
    private var nextId = 1
    private var firstFrame = true
    private var stopRequested = false
    private var gopNanos = 2000 * MS

    private fun at(delay: Long, action: () -> Unit) {
        queue += Scheduled(now + delay, seq++, action)
    }

    /** Starts the pipeline with [config] at [startNanos], as `CapturePipeline.start` does. */
    fun start(config: CaptureConfig, startNanos: Long) {
        this.config = config
        now = startNanos
        gopNanos = config.gopMs * MS
        rung = config.rung(config.startRung)
        pacer = Pacer(config.pacedFps(rung))
        at(1 * MS) {
            probe.onRung(
                RungEvent(
                    now,
                    rung.index,
                    rung.index,
                    rung.width,
                    rung.height,
                    rung.fps,
                    rung.videoKbps,
                    true,
                )
            )
            want(rung)
        }
        at(10 * MS) {
            probe.onCamera(CameraEvent(now, CapturePath.A, 640, 480, 270, 270, true, 1, false))
        }
        if (cameraDelivers) at(50 * MS) { frame() }
    }

    /** Runs [action] at [atNanos] on the fake clock, among the pipeline's events. */
    fun inject(atNanos: Long, action: () -> Unit) {
        queue += Scheduled(atNanos, seq++, action)
    }

    /** Delivers events and due advances until the probe has written its `end` line. */
    fun run(limitNanos: Long = 400 * SECOND) {
        while (!probe.finished) {
            val next = queue.peek()
            val due = probe.nextDue()
            if (next == null && due == null) error("the probe waits on nothing at $now")
            if (due != null && (next == null || due < next.at)) {
                now = maxOf(now, due)
                probe.advance(now)
            } else {
                queue.poll()
                now = next!!.at
                next.action()
            }
            check(now < limitNanos) { "the probe ran past ${limitNanos / SECOND} s" }
        }
    }

    override fun setRung(index: Int) {
        calls += now to "setRung $index"
        at(1 * MS) {
            val next = checkNotNull(config).rung(index)
            val recreates = next.width != rung.width || next.height != rung.height
            rung = next
            pacer.configure(next.fps)
            probe.onRung(
                RungEvent(
                    now,
                    index,
                    index,
                    next.width,
                    next.height,
                    next.fps,
                    next.videoKbps,
                    recreates,
                )
            )
            if (recreates) {
                attached = null
                want(next)
            }
        }
    }

    override fun requestKeyframe() {
        calls += now to "keyframe"
        at(1 * MS) {
            val encoder = attached ?: return@at
            encoder.requestedAt = now
            probe.onKeyframe(KeyframeEvent(now, encoder.id, KeyframeEvent.Kind.REQUESTED))
        }
    }

    override fun requestBitrate(kbps: Int) {
        calls += now to "bitrate $kbps"
        at(1 * MS) {
            val encoder = attached ?: return@at
            encoder.kbps = kbps
            probe.onBitrate(BitrateEvent(now, encoder.id, kbps * 1000, kbps * 1000, 1..20_000_000))
        }
    }

    override fun stop() {
        calls += now to "stop"
        at(1 * MS) {
            stopRequested = true
            attached = null
            val current = live
            if (current == null) at(1 * MS) { probe.onStopped(now) } else stopEncoder(current)
        }
    }

    private fun want(next: Rung) {
        wanted = next
        val current = live
        if (current != null) stopEncoder(current) else at(5 * MS) { create() }
    }

    private fun stopEncoder(encoder: Encoder) {
        if (encoder.stopping) return
        encoder.stopping = true
        if (encoder.pending == 0) at(2 * MS) { stopped(encoder) }
    }

    private fun stopped(encoder: Encoder) {
        probe.onEncoderStopped(EncoderStopped(now, encoder.id, drained = true))
        live = null
        if (stopRequested) {
            at(1 * MS) { probe.onStopped(now) }
        } else if (wanted != null) {
            at(5 * MS) { create() }
        }
    }

    private fun create() {
        val r = wanted ?: return
        wanted = null
        val id = nextId++
        val asked =
            linkedMapOf(
                "mime" to "video/avc",
                "width" to "${r.width}",
                "height" to "${r.height}",
                "color-format" to "2130708361",
                "bitrate" to "${r.videoKbps * 1000}",
                "frame-rate" to "${r.fps}",
                "i-frame-interval" to "2.0",
                "bitrate-mode" to "2",
                "profile" to "65536",
                "level" to "512",
                "latency" to "1",
                "priority" to "0",
                "max-bframes" to "0",
            )
        probe.onFormat(FormatEvent(now, id, FormatEvent.Kind.ASKED, 0, asked))
        probe.onFormat(FormatEvent(now, id, FormatEvent.Kind.INPUT_READ_BACK, 0, asked))
        probe.onFormat(
            FormatEvent(
                now,
                id,
                FormatEvent.Kind.OUTPUT_READ_BACK,
                0,
                linkedMapOf("bitrate" to "${r.videoKbps * 1000}", "mime" to "video/avc"),
            )
        )
        val event =
            EncoderStarted(
                now,
                id,
                "c2.fake.avc.encoder",
                true,
                r.width,
                r.height,
                r.fps,
                r.videoKbps * 1000,
                2000,
                true,
                0,
                1..20_000_000,
                3 * MS,
            )
        started += event
        probe.onEncoderStarted(event)
        val encoder = Encoder(id, r, r.videoKbps)
        live = encoder
        at(1 * MS) { if (wanted == null && !stopRequested) attached = encoder }
    }

    private fun frame() {
        if (stopRequested) return
        val ts = now
        if (firstFrame) {
            firstFrame = false
            probe.onClock(ClockEvent(now, ts, CameraClockDomain.BOOTTIME, 1))
            at(400 * MS) {
                probe.onExposure(
                    ExposureEvent(
                        now,
                        true,
                        false,
                        0.5f,
                        0.5f,
                        true,
                        2,
                        1.0 / 6,
                        -12..12,
                        1.0 / 3,
                        true,
                    )
                )
            }
        }
        probe.onFrame(FrameEvent(now, FrameEvent.Kind.CAPTURED, ts, 0, rung.index))
        val encoder = attached
        when {
            pacer.decide(ts) != PaceDecision.KEEP ->
                probe.onFrame(
                    FrameEvent(now, FrameEvent.Kind.DROPPED, ts, 0, rung.index, DropReason.PACING)
                )
            encoder == null ->
                probe.onFrame(
                    FrameEvent(
                        now,
                        FrameEvent.Kind.DROPPED,
                        ts,
                        0,
                        rung.index,
                        DropReason.NO_ENCODER,
                    )
                )
            else -> {
                submittedTo[encoder.id] = (submittedTo[encoder.id] ?: 0) + 1
                probe.onFrame(
                    FrameEvent(
                        now,
                        FrameEvent.Kind.SUBMITTED,
                        ts,
                        0,
                        rung.index,
                        encoderId = encoder.id,
                    )
                )
                encoder.pending++
                at(encodeDelayNanos) { output(encoder, ts) }
            }
        }
        at(SECOND / cameraFps) { frame() }
    }

    private fun output(encoder: Encoder, ts: Long) {
        val requested = encoder.requestedAt
        val answers = requested != null && ts > requested
        val key = encoder.outputs == 0 || answers || ts - encoder.lastIdr >= gopNanos
        if (encoder.outputs == 0) {
            probe.onParameterSets(
                ParameterSetsChanged(now, encoder.id, SPS, PPS, "avc1.42E01F", fromFormat = false)
            )
            probe.onOutputBuffer(
                OutputBuffer(
                    now,
                    encoder.id,
                    0,
                    0,
                    20,
                    2,
                    false,
                    true,
                    false,
                    listOf(7, 8),
                    false,
                    null,
                    false,
                )
            )
        }
        encoder.outputs++
        val sliceBytes = maxOf(16, encoder.kbps * 1000 / 8 / encoder.rung.fps)
        val slice = byteArrayOf(if (key) 0x65 else 0x41) + FRAME_MARKER + ByteArray(sliceBytes)
        val au = if (key) stream4(SPS, PPS, slice) else stream4(slice)
        probe.onOutputBuffer(
            OutputBuffer(
                atNanos = now,
                encoderId = encoder.id,
                presentationTimeUs = ts / 1000,
                ptsMs = 0,
                size = au.size,
                flags = if (key) 1 else 0,
                keyFlag = key,
                codecConfig = false,
                endOfStream = false,
                nalTypes = if (key) listOf(7, 8, 5) else listOf(1),
                idr = key,
                accessUnit = au,
                paramSets = key,
            )
        )
        if (key) {
            encoder.lastIdr = ts
            if (answers) {
                encoder.requestedAt = null
                probe.onKeyframe(KeyframeEvent(now, encoder.id, KeyframeEvent.Kind.ANSWERED, 20, 1))
            }
        }
        encoder.pending--
        if (encoder.stopping && encoder.pending == 0) at(2 * MS) { stopped(encoder) }
    }
}

/** A probe of [schedule] over a [FakePipeline], its lines collected; not yet started. */
class SimulatedProbe(
    schedule: ProbeSchedule = ProbeSchedule.ONE,
    report: ProbeReport = report(),
    args: ProbeArgs = ProbeArgs(),
    caps: DeviceCaps? = null,
    val pipeline: FakePipeline = FakePipeline(),
) {
    val lines = ArrayList<String>()
    val probe =
        EncoderProbe(schedule, device(report), args, caps, pipeline, FakeReadings()) { lines += it }

    init {
        pipeline.probe = probe
    }

    /** Starts at 5 s and runs until the `end` line. */
    fun run(): List<JsonObject> {
        probe.start(START)
        pipeline.start(probe.captureConfig(), START)
        pipeline.run()
        return parseLog(lines)
    }

    companion object {
        /** When a simulated probe starts: `t_us` 0. */
        const val START: Long = 5 * SECOND
    }
}
