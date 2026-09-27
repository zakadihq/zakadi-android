@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference
import dev.zakadi.sdk.device.CapabilityProbe
import dev.zakadi.sdk.device.CheckOutcome
import dev.zakadi.sdk.device.DeviceCaps
import dev.zakadi.sdk.device.ProbeReport
import dev.zakadi.sdk.device.deviceCaps

/**
 * One AVC encoder the platform lists, as the `encoders` of the `device` line carry it (spec 07
 * section 7.19); a value the platform cannot read is null.
 */
data class ProbeEncoder(
    val name: String,
    /** Hardware under the rule of spec 07 section 7.19. */
    val hw: Boolean?,
    /** `MediaCodecInfo.isVendor()`, from API 29. */
    val vendor: Boolean?,
    /** `MediaCodecInfo.isAlias()`, from API 29. */
    val alias: Boolean?,
    /** `BITRATE_MODE_CBR` listed. */
    val cbr: Boolean?,
    /** Constrained Baseline listed among the profiles. */
    val cb: Boolean?,
    /** The highest level listed for Baseline or Constrained Baseline, as `3.1`. */
    val maxLevel: String?,
    /** The rungs of the ladder whose size passes `isSizeSupported`. */
    val rungs: List<Int>?,
    /** The bitrate range in kbit/s: lowest and highest. */
    val kbpsRange: List<Double>?,
    /** `getAchievableFrameRatesFor(480, 640)`: lowest and highest, or null when unmeasured. */
    val achievableFps: List<Double>?,
)

/**
 * The facts of the `device` line: the model and build named in log format 1, the tier and checks of
 * the capability probe (spec 07 section 7.26), the AVC encoders, and the thermal and battery state.
 * Nothing else about the device or its user.
 */
data class ProbeDevice(
    /** Unix time in milliseconds when the line was written; it names the log file too. */
    val wallMs: Long,
    /** `Build.MANUFACTURER + " " + Build.MODEL`, the `model_prefix` subject of 7.26. */
    val phone: String?,
    /** `SOC_MANUFACTURER + " " + SOC_MODEL` from API 31, `Build.HARDWARE` below. */
    val soc: String?,
    /** `Build.VERSION.RELEASE`. */
    val os: String?,
    /** `Build.FINGERPRINT`. */
    val osBuild: String?,
    /** `U`, `L` or `S`. */
    val tier: String?,
    val lowRam: Boolean?,
    val memMb: Long?,
    /** The front camera's hardware level, as `LIMITED`. */
    val cameraLevel: String?,
    val eglRecordable: Boolean?,
    val frontCamera: Boolean?,
    /** The probe's configure, start and stop at 480x640, in milliseconds. */
    val configureMs: Double?,
    val encoders: List<ProbeEncoder>,
    /** 7.7's name for the thermal state; null below API 29. */
    val thermal: String?,
    val batteryPct: Int?,
    val charging: Boolean?,
) {
    companion object {
        private const val LEVEL_PREFIX = "hardware level "

        /**
         * The facts of [report] (spec 07 section 7.26) with those the caller reads: the tier of its
         * facts, the low-RAM flag and total memory, the camera hardware level, the EGL and front
         * camera checks, and the timed encoder check when it passed.
         */
        fun of(
            report: ProbeReport,
            wallMs: Long,
            phone: String?,
            soc: String?,
            os: String?,
            osBuild: String?,
            encoders: List<ProbeEncoder>,
            thermal: String?,
            batteryPct: Int?,
            charging: Boolean?,
        ): ProbeDevice {
            val checks = report.checks.associateBy { it.name }
            val level = checks[CapabilityProbe.CAMERA_LEVEL]
            val encoder = checks[CapabilityProbe.AVC_ENCODER]
            return ProbeDevice(
                wallMs = wallMs,
                phone = phone,
                soc = soc,
                os = os,
                osBuild = osBuild,
                tier = deviceCaps(report.facts).tier.name,
                lowRam = report.facts.lowRamDevice,
                memMb = report.facts.totalMemBytes / BYTES_PER_MB,
                cameraLevel =
                    level
                        ?.takeIf {
                            it.outcome != CheckOutcome.NOT_RUN && it.detail.startsWith(LEVEL_PREFIX)
                        }
                        ?.detail
                        ?.removePrefix(LEVEL_PREFIX),
                eglRecordable = report.facts.eglRecordable,
                frontCamera = report.facts.frontCamera,
                configureMs =
                    encoder
                        ?.takeIf { it.outcome == CheckOutcome.PASSED }
                        ?.durationNanos
                        ?.let { it / 1_000_000.0 },
                encoders = encoders,
                thermal = thermal,
                batteryPct = batteryPct,
                charging = charging,
            )
        }

        private const val BYTES_PER_MB = 1_048_576L
    }
}

/**
 * The arguments of one probe invocation, which the `device` line records as `args`: the encoder and
 * capture path asked for, and the divisor of a shortened schedule (1 as specified).
 */
data class ProbeArgs(
    val encoder: EncoderPreference = EncoderPreference.HARDWARE,
    val path: CapturePath = CapturePath.A,
    val divisor: Int = 1,
) {
    /**
     * `args`, with the caps the tier and any quirk would set (spec 07 section 7.26) under `caps`:
     * logged, and never applied to the schedule.
     */
    fun json(caps: DeviceCaps?): Map<String, Any?> =
        linkedMapOf(
            "encoder" to encoder.name.lowercase(),
            "path" to path.name,
            "divisor" to divisor,
            "caps" to
                caps?.let {
                    linkedMapOf(
                        "best_rung" to it.bestRung,
                        "max_fps" to it.maxFps,
                        "path" to it.path?.name,
                        "prefer_software_encoder" to (it.encoder == EncoderPreference.SOFTWARE),
                    )
                },
        )
}

/** 7.7's name for a `PowerManager` thermal status: null for none or an unknown value. */
fun thermalName(status: Int): String? =
    when (status) {
        0,
        1 -> "nominal"
        2 -> "fair"
        3 -> "serious"
        in 4..6 -> "critical"
        else -> null
    }

/** The `CodecProfileLevel` AVC levels by name, lowest first. */
private val AVC_LEVELS =
    listOf(
        0x01 to "1",
        0x02 to "1b",
        0x04 to "1.1",
        0x08 to "1.2",
        0x10 to "1.3",
        0x20 to "2",
        0x40 to "2.1",
        0x80 to "2.2",
        0x100 to "3",
        0x200 to "3.1",
        0x400 to "3.2",
        0x800 to "4",
        0x1000 to "4.1",
        0x2000 to "4.2",
        0x4000 to "5",
        0x8000 to "5.1",
        0x10000 to "5.2",
        0x20000 to "6",
        0x40000 to "6.1",
        0x80000 to "6.2",
    )

/** The name of a `CodecProfileLevel` AVC level, as `3.1`; null for an unknown value. */
fun avcLevelName(level: Int): String? = AVC_LEVELS.firstOrNull { it.first == level }?.second

/** The `profile` of a `run` line for a `CodecProfileLevel` AVC profile value. */
fun avcProfileName(profile: Int): String? =
    when (profile) {
        0x10000 -> "constrained_baseline"
        0x01 -> "baseline"
        else -> null
    }

/** The `bitrate_mode` of a `run` line for an `EncoderCapabilities` bitrate mode. */
fun bitrateModeName(mode: Int): String? =
    when (mode) {
        2 -> "cbr"
        1 -> "vbr"
        else -> null
    }
