package dev.zakadi.sdk.probe

/** One `in`, `out` or `kf_req` line of a run: the lines the summaries read. */
data class ProbeRecord(
    val kind: Kind,
    /** `t_us` of the line. */
    val tUs: Long,
    /** `pts_us` of an `in` or `out` line. */
    val ptsUs: Long = 0,
    /** `bytes` of an `out` line. */
    val bytes: Int = 0,
    /** `key` of an `out` line. */
    val key: Boolean = false,
    /** `repeat` of a `kf_req` line. */
    val repeat: Boolean = false,
) {
    enum class Kind {
        IN,
        OUT,
        KF_REQ,
    }

    companion object {
        fun input(tUs: Long, ptsUs: Long) = ProbeRecord(Kind.IN, tUs, ptsUs)

        fun output(tUs: Long, ptsUs: Long, bytes: Int, key: Boolean) =
            ProbeRecord(Kind.OUT, tUs, ptsUs, bytes, key)

        fun keyframeRequest(tUs: Long, repeat: Boolean) =
            ProbeRecord(Kind.KF_REQ, tUs, repeat = repeat)
    }
}

/**
 * The summaries of a `run_end` line as Z-059 computes them from the run's lines, in line order;
 * each null where the lines give no value.
 * - `delivered_kbps`: 8 times the `bytes` of the `out` lines from the first `key` one, over their
 *   `pts_us` span, in kbit/s.
 * - `kf_latency_ms`: the median over `kf_req` lines with `repeat` false of the `t_us` gap to the
 *   next `out` with `key` true; requests with none are left out.
 * - `kf_frames`: the median count of `in` lines after such a request up to the frame of that `out`.
 * - `min_fps`: the least count of `out` lines in a window of 1000000 us of `pts_us` that opens on
 *   an `out` line at or after the first `key` one and ends by the last.
 */
data class ProbeSummary(
    val deliveredKbps: Double?,
    val kfLatencyMs: Double?,
    val kfFrames: Double?,
    val minFps: Int?,
) {
    companion object {
        private const val WINDOW_US = 1_000_000L

        /** The summaries of one run's [records], in line order. */
        fun of(records: List<ProbeRecord>): ProbeSummary {
            val delivered = records.filter { it.kind == ProbeRecord.Kind.OUT }.dropWhile { !it.key }
            val latencies = ArrayList<Double>()
            val frames = ArrayList<Double>()
            for ((index, request) in records.withIndex()) {
                if (request.kind != ProbeRecord.Kind.KF_REQ || request.repeat) continue
                val found =
                    (index + 1 until records.size).firstOrNull {
                        records[it].kind == ProbeRecord.Kind.OUT && records[it].key
                    } ?: continue
                val answer = records[found]
                latencies += (answer.tUs - request.tUs) / 1000.0
                frames +=
                    (index + 1 until found)
                        .count {
                            records[it].kind == ProbeRecord.Kind.IN &&
                                records[it].ptsUs <= answer.ptsUs
                        }
                        .toDouble()
            }
            return ProbeSummary(
                deliveredKbps(delivered),
                median(latencies),
                median(frames),
                minFps(delivered),
            )
        }

        private fun deliveredKbps(outputs: List<ProbeRecord>): Double? {
            val first = outputs.firstOrNull() ?: return null
            val span = outputs.last().ptsUs - first.ptsUs
            if (span <= 0) return null
            val bits = outputs.sumOf { it.bytes.toLong() } * 8
            return bits * 1000.0 / span
        }

        private fun minFps(outputs: List<ProbeRecord>): Int? {
            val last = outputs.lastOrNull()?.ptsUs ?: return null
            var least: Int? = null
            for ((index, start) in outputs.withIndex()) {
                if (start.ptsUs + WINDOW_US > last) continue
                val end = start.ptsUs + WINDOW_US
                val count = outputs.subList(index, outputs.size).takeWhile { it.ptsUs < end }.size
                least = minOf(least ?: count, count)
            }
            return least
        }

        private fun median(values: List<Double>): Double? {
            if (values.isEmpty()) return null
            val sorted = values.sorted()
            val middle = sorted.size / 2
            return if (sorted.size % 2 == 1) sorted[middle]
            else (sorted[middle - 1] + sorted[middle]) / 2
        }
    }
}
