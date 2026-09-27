@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.BatteryManager
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.PowerManager
import android.os.Process
import android.os.SystemClock
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.CapturePipeline
import dev.zakadi.sdk.capture.Rung
import dev.zakadi.sdk.capture.aeTargetFpsRange
import dev.zakadi.sdk.device.CapabilityProbe
import dev.zakadi.sdk.device.DeviceCaps
import dev.zakadi.sdk.device.DeviceIdentity
import dev.zakadi.sdk.device.deviceCaps
import dev.zakadi.sdk.encode.AVC_PROFILE_CONSTRAINED_BASELINE
import dev.zakadi.sdk.encode.isSoftwareEncoder
import dev.zakadi.sdk.encode.toCandidate
import java.io.File
import java.io.IOException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executor
import java.util.concurrent.TimeUnit
import kotlin.math.ceil

/**
 * The encoder probe on this device (phase 0 measurement 6, D105): the facts of the `device` line,
 * read from the build, the capability probe of spec 07 section 7.26 and the codec list, and a
 * [CapturePipeline] on the front camera under an [EncoderProbe] on a thread of its own.
 */
object AndroidProbe {
    /** Under the test app's external files, where `adb pull` reaches the logs. */
    const val DIRECTORY: String = "zakadi-probe"

    /** `/sdcard/Android/data/dev.zakadi.sdk.test/files/zakadi-probe/` on most phones. */
    fun logDirectory(context: Context): File =
        checkNotNull(context.getExternalFilesDir(DIRECTORY)) {
            "no external storage for the probe's logs"
        }

    /**
     * Runs [schedule] with [args] on the front camera, with the caller's activity in the
     * foreground, and returns `probe-<unix ms>.jsonl` in [directory] once its `end` line is
     * written. The capability probe runs first, as the SDK's PREFLIGHT would; its tier is logged
     * and not applied, and no `device_quirks` are read.
     */
    fun run(context: Context, args: ProbeArgs, schedule: ProbeSchedule, directory: File): File {
        val report = CapabilityProbe(context).run()
        val readings = AndroidReadings(context)
        val wallMs = System.currentTimeMillis()
        val device =
            ProbeDevice.of(
                report,
                wallMs = wallMs,
                phone = DeviceIdentity.current().modelName,
                soc = soc(),
                os = Build.VERSION.RELEASE,
                osBuild = Build.FINGERPRINT,
                encoders = avcEncoders(schedule.ladder),
                thermal = readings.thermal(),
                batteryPct = readings.batteryPercent(),
                charging = readings.charging(),
            )
        check(directory.isDirectory || directory.mkdirs()) { "cannot create $directory" }
        val file = File(directory, "probe-$wallMs.jsonl")
        val caps = deviceCaps(report.facts)
        Session(context, schedule, device, args, caps, readings, file)
            .run(report.facts.frontCamera, timeoutMs(schedule))
        return file
    }

    /** Every AVC encoder of `REGULAR_CODECS`, the list spec 07 section 7.19 picks from. */
    fun avcEncoders(ladder: List<Rung>): List<ProbeEncoder> =
        MediaCodecList(MediaCodecList.REGULAR_CODECS)
            .codecInfos
            .filter {
                it.isEncoder &&
                    it.supportedTypes.any { t -> t.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
            }
            .map { entry(it, ladder) }

    private fun entry(info: MediaCodecInfo, ladder: List<Rung>): ProbeEncoder {
        val caps =
            try {
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            } catch (_: IllegalArgumentException) {
                null
            }
        val video = caps?.videoCapabilities
        val profiles = caps?.profileLevels.orEmpty()
        val baseline = profiles.filter {
            it.profile == CodecProfileLevel.AVCProfileBaseline ||
                it.profile == AVC_PROFILE_CONSTRAINED_BASELINE
        }
        val api29 = Build.VERSION.SDK_INT >= 29
        return ProbeEncoder(
            name = info.name,
            hw = !isSoftwareEncoder(info.toCandidate(), Build.VERSION.SDK_INT),
            vendor = if (api29) info.isVendor else null,
            alias = if (api29) info.isAlias else null,
            cbr =
                caps
                    ?.encoderCapabilities
                    ?.isBitrateModeSupported(EncoderCapabilities.BITRATE_MODE_CBR),
            cb = caps?.let { profiles.any { it.profile == AVC_PROFILE_CONSTRAINED_BASELINE } },
            maxLevel = baseline.maxOfOrNull { it.level }?.let(::avcLevelName),
            rungs =
                video?.let { v ->
                    ladder.filter { v.isSizeSupported(it.width, it.height) }.map { it.index }
                },
            kbpsRange = video?.bitrateRange?.let { listOf(it.lower / 1000.0, it.upper / 1000.0) },
            achievableFps =
                video?.let { v ->
                    try {
                        v.getAchievableFrameRatesFor(480, 640)?.let { listOf(it.lower, it.upper) }
                    } catch (_: IllegalArgumentException) {
                        null
                    }
                },
        )
    }

    /** `SOC_MANUFACTURER + " " + SOC_MODEL` from API 31, `Build.HARDWARE` below. */
    private fun soc(): String =
        if (Build.VERSION.SDK_INT >= 31) "${Build.SOC_MANUFACTURER} ${Build.SOC_MODEL}"
        else Build.HARDWARE

    /** The schedule's durations, 15 s a run for starts and drains, and a minute to spare. */
    private fun timeoutMs(schedule: ProbeSchedule): Long =
        schedule.runs.sumOf { it.durationMs + 15_000 } + 60_000

    private fun now(): Long = SystemClock.elapsedRealtimeNanos()

    /** One probe: its thread, its log and its pipeline. */
    private class Session(
        context: Context,
        schedule: ProbeSchedule,
        device: ProbeDevice,
        args: ProbeArgs,
        caps: DeviceCaps,
        readings: ProbeReadings,
        file: File,
    ) {
        private val thread = HandlerThread("zakadi-probe").apply { start() }
        private val handler = Handler(thread.looper)
        private val writer = file.bufferedWriter(Charsets.US_ASCII)
        private val done = CountDownLatch(1)
        private var open = true
        private var failure: IOException? = null
        private val timer = Runnable {
            probe.advance(now())
            after()
        }
        private val pipeline: CapturePipeline
        private val probe: EncoderProbe

        init {
            val target =
                object : ProbeTarget {
                    override fun setRung(index: Int) = pipeline.setRung(index)

                    override fun requestKeyframe() = pipeline.requestKeyframe()

                    override fun requestBitrate(kbps: Int) = pipeline.requestBitrate(kbps)

                    override fun stop() = pipeline.stop {
                        handler.post {
                            probe.onStopped(now())
                            after()
                        }
                    }
                }
            probe = EncoderProbe(schedule, device, args, caps, target, readings, ::write)
            pipeline =
                CapturePipeline(
                    context,
                    RelayListener(Executor { handler.post(it) }, probe, ::after),
                )
        }

        private fun write(line: String) {
            if (!open || failure != null) return
            try {
                writer.write(line)
                writer.write("\n")
            } catch (e: IOException) {
                failure = e
            }
        }

        /** Runs the probe to its `end` line, or stops it after [timeoutMs]. */
        fun run(frontCamera: Boolean, timeoutMs: Long) {
            handler.post {
                probe.start(now())
                if (frontCamera) pipeline.start(probe.captureConfig()) else probe.unsupported()
                after()
            }
            val finished = done.await(timeoutMs, TimeUnit.MILLISECONDS)
            if (!finished) {
                val stopped = CountDownLatch(1)
                pipeline.stop { stopped.countDown() }
                stopped.await(STOP_WAIT_S, TimeUnit.SECONDS)
            }
            val closed = CountDownLatch(1)
            handler.post {
                open = false
                try {
                    writer.close()
                } catch (e: IOException) {
                    failure = failure ?: e
                }
                closed.countDown()
            }
            closed.await(STOP_WAIT_S, TimeUnit.SECONDS)
            thread.quitSafely()
            failure?.let { throw it }
            check(finished) { "the probe did not end within $timeoutMs ms" }
        }

        /** After each event: the log flushed once it ends, else the timer set for what is due. */
        private fun after() {
            handler.removeCallbacks(timer)
            if (probe.finished) {
                try {
                    writer.flush()
                } catch (e: IOException) {
                    failure = failure ?: e
                }
                done.countDown()
                return
            }
            val due = probe.nextDue() ?: return
            handler.postDelayed(timer, maxOf(0L, ceil((due - now()) / 1e6).toLong()))
        }

        private companion object {
            const val STOP_WAIT_S = 10L
        }
    }
}

/** What the probe reads of this device while it runs. */
class AndroidReadings(context: Context) : ProbeReadings {
    private val power = context.getSystemService(PowerManager::class.java)
    private val battery = context.getSystemService(BatteryManager::class.java)
    private val frontRanges = frontCameraFpsRanges(context)

    /** 7.7's name for `PowerManager.getCurrentThermalStatus()`, which takes API 29. */
    override fun thermal(): String? =
        if (Build.VERSION.SDK_INT >= 29 && power != null) thermalName(power.currentThermalStatus)
        else null

    override fun batteryPercent(): Int? =
        battery?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY)?.takeIf { it in 0..100 }

    fun charging(): Boolean? = battery?.isCharging

    /** `Process.getElapsedCpuTime()`: this process's CPU time. */
    override fun cpuTimeMs(): Long = Process.getElapsedCpuTime()

    /**
     * The range the pipeline asks for with no `max_fps`: path A's `VideoCapture` target of 15 to
     * 30, path B's AE target range among the front camera's (spec 07 section 7.18).
     */
    override fun cameraFps(path: CapturePath): IntRange? =
        when (path) {
            CapturePath.A -> MIN_FPS..MAX_FPS
            CapturePath.B -> aeTargetFpsRange(frontRanges, MAX_FPS)
        }

    private companion object {
        const val MIN_FPS = 15
        const val MAX_FPS = 30

        fun frontCameraFpsRanges(context: Context): List<IntRange> {
            val manager = context.getSystemService(CameraManager::class.java) ?: return emptyList()
            return try {
                manager.cameraIdList
                    .map { manager.getCameraCharacteristics(it) }
                    .firstOrNull {
                        it.get(CameraCharacteristics.LENS_FACING) ==
                            CameraCharacteristics.LENS_FACING_FRONT
                    }
                    ?.get(CameraCharacteristics.CONTROL_AE_AVAILABLE_TARGET_FPS_RANGES)
                    ?.map { it.lower..it.upper }
                    .orEmpty()
            } catch (_: Exception) {
                emptyList()
            }
        }
    }
}
