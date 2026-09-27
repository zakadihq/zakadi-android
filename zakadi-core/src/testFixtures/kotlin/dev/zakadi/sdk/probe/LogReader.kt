package dev.zakadi.sdk.probe

import kotlinx.serialization.SerializationException
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.longOrNull

/**
 * Reads a log of format 1 back as Z-059 reads it, for the tests: ASCII, one JSON object per line
 * ending in `\n`, each with the keys of its kind in the order listed, `v` 1 and `t_us` a count of
 * microseconds; the `device` line first and the `end` line last; one `run` and one `run_end` per
 * run, numbered from 0, the `run` line before the run's other lines and the `run_end` line after. A
 * log that breaks any of it throws, naming the line.
 */
object LogReader {
    /** The keys of every line kind of log format 1, in order (Z-064's Spec). */
    val KEYS: Map<String, List<String>> =
        mapOf(
            "device" to
                listOf(
                    "v",
                    "kind",
                    "t_us",
                    "wall_ms",
                    "platform",
                    "phone",
                    "soc",
                    "os",
                    "os_build",
                    "schedule",
                    "args",
                    "tier",
                    "low_ram",
                    "mem_mb",
                    "camera_level",
                    "egl_recordable",
                    "front_camera",
                    "configure_ms",
                    "encoders",
                    "thermal",
                    "battery_pct",
                    "charging",
                ),
            "run" to
                listOf(
                    "v",
                    "kind",
                    "t_us",
                    "run",
                    "mode",
                    "rung",
                    "w",
                    "h",
                    "fps",
                    "kbps",
                    "gop_ms",
                    "path",
                    "encoder",
                    "hw",
                    "profile",
                    "level",
                    "bitrate_mode",
                    "dropped",
                    "out_format",
                    "camera",
                    "t0_us",
                    "thermal",
                ),
            "params" to listOf("v", "kind", "t_us", "run", "source", "sps", "pps", "codec"),
            "in" to listOf("v", "kind", "t_us", "run", "pts_us"),
            "out" to
                listOf(
                    "v",
                    "kind",
                    "t_us",
                    "run",
                    "pts_us",
                    "bytes",
                    "key",
                    "flag_key",
                    "param_sets",
                    "sc3",
                    "nal",
                ),
            "kf_req" to listOf("v", "kind", "t_us", "run", "n", "repeat"),
            "rate" to listOf("v", "kind", "t_us", "run", "kbps"),
            "tick" to
                listOf(
                    "v",
                    "kind",
                    "t_us",
                    "run",
                    "captured",
                    "submitted",
                    "encoded",
                    "pre_encode_drops",
                    "enc_queue",
                    "encoded_kbps",
                    "thermal",
                    "cpu_ms",
                    "enc_dropped",
                ),
            "run_end" to
                listOf(
                    "v",
                    "kind",
                    "t_us",
                    "run",
                    "in",
                    "out",
                    "idr",
                    "idr_bare",
                    "delivered_kbps",
                    "kf_latency_ms",
                    "kf_frames",
                    "min_fps",
                    "error",
                ),
            "end" to listOf("v", "kind", "t_us", "runs", "reason", "thermal", "battery_pct"),
        )

    /** The keys of each entry of the `device` line's `encoders`, in order. */
    val ENCODER_KEYS: List<String> =
        listOf(
            "name",
            "hw",
            "vendor",
            "alias",
            "cbr",
            "cb",
            "max_level",
            "rungs",
            "kbps_range",
            "achievable_fps",
        )

    /** The keys of a `run` line's `camera`, in order. */
    val CAMERA_KEYS: List<String> =
        listOf("w", "h", "rotation", "fps_min", "fps_max", "ev", "clock")

    /** The lines of [text], each checked against its kind, and the log's structure checked. */
    fun read(text: String): List<JsonObject> {
        check(text.all { it == '\n' || it.code in 0x20..0x7E }) { "the log is not ASCII" }
        check(text.endsWith("\n")) { "the last line has no \\n" }
        val lines = text.removeSuffix("\n").split("\n").mapIndexed { i, line -> line(i + 1, line) }
        structure(lines)
        return lines
    }

    private fun line(number: Int, text: String): JsonObject {
        val line =
            try {
                Json.parseToJsonElement(text) as? JsonObject
            } catch (e: SerializationException) {
                throw IllegalStateException("line $number: not JSON: ${e.message}")
            } ?: error("line $number: not an object")
        val kind = (line["kind"] as? JsonPrimitive)?.takeIf { it.isString }?.content
        val keys = KEYS[kind] ?: error("line $number: no kind $kind")
        check(line.keys.toList() == keys) { "line $number: keys ${line.keys}, not $keys" }
        check((line.getValue("v") as? JsonPrimitive)?.intOrNull == LogLines.VERSION) {
            "line $number: v is not ${LogLines.VERSION}"
        }
        val tUs = (line.getValue("t_us") as? JsonPrimitive)?.longOrNull
        check(tUs != null && tUs >= 0) { "line $number: t_us is not a count of microseconds" }
        if (kind == "device") {
            val encoders =
                line.getValue("encoders") as? JsonArray ?: error("line $number: encoders")
            for (encoder in encoders) {
                check((encoder as? JsonObject)?.keys?.toList() == ENCODER_KEYS) {
                    "line $number: an encoder's keys"
                }
            }
        }
        if (kind == "run") {
            val camera = line.getValue("camera")
            check(camera is JsonNull || (camera as? JsonObject)?.keys?.toList() == CAMERA_KEYS) {
                "line $number: the camera's keys"
            }
        }
        return line
    }

    private fun structure(lines: List<JsonObject>) {
        check(lines.isNotEmpty() && kind(lines.first()) == "device") { "line 1: not device" }
        check(kind(lines.last()) == "end") { "line ${lines.size}: not end" }
        check(lines.count { kind(it) == "device" } == 1) { "more than one device line" }
        check(lines.count { kind(it) == "end" } == 1) { "more than one end line" }
        val open = HashSet<Int>()
        val closed = ArrayList<Int>()
        var started = 0
        for ((i, line) in lines.withIndex()) {
            val kind = kind(line)
            if (kind == "device" || kind == "end") continue
            val run = (line.getValue("run") as? JsonPrimitive)?.intOrNull
            checkNotNull(run) { "line ${i + 1}: run is not a number" }
            when (kind) {
                "run" -> {
                    check(run == started) { "line ${i + 1}: run $run, expected $started" }
                    started++
                    open += run
                }
                "run_end" -> {
                    check(open.remove(run)) { "line ${i + 1}: run_end of run $run not open" }
                    closed += run
                }
                else -> check(run in open) { "line ${i + 1}: $kind of run $run not open" }
            }
        }
        check(open.isEmpty()) { "runs $open have no run_end" }
        val runs = (lines.last().getValue("runs") as? JsonPrimitive)?.intOrNull
        check(runs == closed.size) { "end counts $runs runs, the log ${closed.size}" }
    }

    private fun kind(line: JsonObject): String = (line.getValue("kind") as JsonPrimitive).content
}
