package dev.zakadi.sdk.probe

/** The camera of a `run` line; a value the platform cannot read is null. */
data class CameraLine(
    val w: Int?,
    val h: Int?,
    val rotation: Int?,
    val fpsMin: Int?,
    val fpsMax: Int?,
    /** The exposure compensation applied, in EV. */
    val ev: Double?,
    /** `boottime`, `monotonic` or `unknown` by the rule of spec 07 section 7.5. */
    val clock: String?,
)

/** What a `run` line says about its run. */
data class RunLine(
    val run: Int,
    val mode: String,
    val rung: Int,
    val w: Int,
    val h: Int,
    val fps: Int,
    val kbps: Int,
    val gopMs: Int,
    val path: String?,
    val encoder: String?,
    val hw: Boolean?,
    /** As requested: `constrained_baseline` or `baseline`; null once dropped. */
    val profile: String?,
    /** As requested: `3.1`; null once dropped. */
    val level: String?,
    /** As requested: `cbr` or `vbr`; null once dropped. */
    val bitrateMode: String?,
    /** The keys the refusal steps of spec 07 section 7.19 dropped, in order. */
    val dropped: List<String>?,
    /** The output format read back after `configure()`, each value as text. */
    val outFormat: Map<String, String>?,
    val camera: CameraLine?,
    /** The first submitted frame on the `t_us` clock; null when the camera clock is another. */
    val t0Us: Long?,
    val thermal: String?,
)

/** An `out` line after its header. */
data class OutLine(
    val run: Int,
    val ptsUs: Long,
    /** The buffer's size as the encoder returned it. */
    val bytes: Int,
    /** The buffer holds NAL type 5. */
    val key: Boolean,
    /** `BUFFER_FLAG_KEY_FRAME`. */
    val flagKey: Boolean,
    /** The buffer holds an SPS and a PPS. */
    val paramSets: Boolean,
    /** 3-byte start codes in the buffer; null when they cannot be told. */
    val sc3: Int?,
    val nal: List<Int>,
)

/** The counters of a `tick` line. */
data class TickCounts(
    val captured: Int,
    val submitted: Int,
    val encoded: Int,
    val preEncodeDrops: Long,
    val encQueue: Long,
    val encodedKbps: Double,
    val thermal: String?,
    val cpuMs: Long?,
    /** Frames the encoder reported dropped: no Android encoder reports them. */
    val encDropped: Int? = null,
)

/** The counts, summaries and error of a `run_end` line. */
data class RunTotals(
    val inputs: Int,
    val outputs: Int,
    val idr: Int,
    val idrBare: Int,
    val summary: ProbeSummary,
    val error: String?,
)

/** Why a probe ended: the `reason` of the `end` line. */
enum class EndReason(val wire: String) {
    DONE("done"),
    UNSUPPORTED_DEVICE("unsupported_device"),
    ERROR("error"),
}

/**
 * Log format 1 of the encoder probe (phase 0 measurement 6, spec 09 section 9.11 item 6, D105), the
 * contract with Z-059: each line kind with its keys in the order listed, `null` where the platform
 * cannot read a value, every line opening with `v`, `kind` and `t_us`, and each line one ASCII JSON
 * object without its `\n`.
 */
object LogLines {
    const val VERSION: Int = 1

    fun device(device: ProbeDevice, schedule: Int, args: Map<String, Any?>): String =
        line(
            "device",
            0,
            "wall_ms" to device.wallMs,
            "platform" to "android",
            "phone" to device.phone,
            "soc" to device.soc,
            "os" to device.os,
            "os_build" to device.osBuild,
            "schedule" to schedule,
            "args" to args,
            "tier" to device.tier,
            "low_ram" to device.lowRam,
            "mem_mb" to device.memMb,
            "camera_level" to device.cameraLevel,
            "egl_recordable" to device.eglRecordable,
            "front_camera" to device.frontCamera,
            "configure_ms" to device.configureMs,
            "encoders" to device.encoders.map(::encoder),
            "thermal" to device.thermal,
            "battery_pct" to device.batteryPct,
            "charging" to device.charging,
        )

    private fun encoder(e: ProbeEncoder): Map<String, Any?> =
        linkedMapOf(
            "name" to e.name,
            "hw" to e.hw,
            "vendor" to e.vendor,
            "alias" to e.alias,
            "cbr" to e.cbr,
            "cb" to e.cb,
            "max_level" to e.maxLevel,
            "rungs" to e.rungs,
            "kbps_range" to e.kbpsRange,
            "achievable_fps" to e.achievableFps,
        )

    fun run(tUs: Long, r: RunLine): String =
        line(
            "run",
            tUs,
            "run" to r.run,
            "mode" to r.mode,
            "rung" to r.rung,
            "w" to r.w,
            "h" to r.h,
            "fps" to r.fps,
            "kbps" to r.kbps,
            "gop_ms" to r.gopMs,
            "path" to r.path,
            "encoder" to r.encoder,
            "hw" to r.hw,
            "profile" to r.profile,
            "level" to r.level,
            "bitrate_mode" to r.bitrateMode,
            "dropped" to r.dropped,
            "out_format" to r.outFormat,
            "camera" to r.camera?.let(::camera),
            "t0_us" to r.t0Us,
            "thermal" to r.thermal,
        )

    private fun camera(c: CameraLine): Map<String, Any?> =
        linkedMapOf(
            "w" to c.w,
            "h" to c.h,
            "rotation" to c.rotation,
            "fps_min" to c.fpsMin,
            "fps_max" to c.fpsMax,
            "ev" to c.ev,
            "clock" to c.clock,
        )

    fun params(
        tUs: Long,
        run: Int,
        source: String,
        sps: ByteArray?,
        pps: ByteArray?,
        codec: String?,
    ): String =
        line(
            "params",
            tUs,
            "run" to run,
            "source" to source,
            "sps" to sps?.let(::hex),
            "pps" to pps?.let(::hex),
            "codec" to codec,
        )

    fun input(tUs: Long, run: Int, ptsUs: Long): String =
        line("in", tUs, "run" to run, "pts_us" to ptsUs)

    fun output(tUs: Long, o: OutLine): String =
        line(
            "out",
            tUs,
            "run" to o.run,
            "pts_us" to o.ptsUs,
            "bytes" to o.bytes,
            "key" to o.key,
            "flag_key" to o.flagKey,
            "param_sets" to o.paramSets,
            "sc3" to o.sc3,
            "nal" to o.nal,
        )

    fun keyframeRequest(tUs: Long, run: Int, n: Int, repeat: Boolean): String =
        line("kf_req", tUs, "run" to run, "n" to n, "repeat" to repeat)

    fun rate(tUs: Long, run: Int, kbps: Int): String =
        line("rate", tUs, "run" to run, "kbps" to kbps)

    fun tick(tUs: Long, run: Int, c: TickCounts): String =
        line(
            "tick",
            tUs,
            "run" to run,
            "captured" to c.captured,
            "submitted" to c.submitted,
            "encoded" to c.encoded,
            "pre_encode_drops" to c.preEncodeDrops,
            "enc_queue" to c.encQueue,
            "encoded_kbps" to c.encodedKbps,
            "thermal" to c.thermal,
            "cpu_ms" to c.cpuMs,
            "enc_dropped" to c.encDropped,
        )

    fun runEnd(tUs: Long, run: Int, t: RunTotals): String =
        line(
            "run_end",
            tUs,
            "run" to run,
            "in" to t.inputs,
            "out" to t.outputs,
            "idr" to t.idr,
            "idr_bare" to t.idrBare,
            "delivered_kbps" to t.summary.deliveredKbps,
            "kf_latency_ms" to t.summary.kfLatencyMs,
            "kf_frames" to t.summary.kfFrames,
            "min_fps" to t.summary.minFps,
            "error" to t.error,
        )

    fun end(tUs: Long, runs: Int, reason: EndReason, thermal: String?, batteryPct: Int?): String =
        line(
            "end",
            tUs,
            "runs" to runs,
            "reason" to reason.wire,
            "thermal" to thermal,
            "battery_pct" to batteryPct,
        )

    /** Lowercase hex, two digits a byte. */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun line(kind: String, tUs: Long, vararg fields: Pair<String, Any?>): String {
        val map = LinkedHashMap<String, Any?>()
        map["v"] = VERSION
        map["kind"] = kind
        map["t_us"] = tUs
        for ((key, value) in fields) map[key] = value
        return LogJson.encode(map)
    }
}
