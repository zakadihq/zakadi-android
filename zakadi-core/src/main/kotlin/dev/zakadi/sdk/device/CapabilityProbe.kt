@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import android.app.ActivityManager
import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Handler
import android.os.HandlerThread
import android.os.SystemClock
import androidx.annotation.WorkerThread
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.EglCore
import dev.zakadi.sdk.encode.AvcCodecSupport
import dev.zakadi.sdk.encode.AvcEncoder
import dev.zakadi.sdk.encode.AvcFormatSpec
import dev.zakadi.sdk.encode.EncoderListener
import dev.zakadi.sdk.encode.pickAvcEncoder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/** How a probe check came out. */
@InternalZakadiApi
enum class CheckOutcome {
    PASSED,
    FAILED,

    /** Not run on this device or in this version. */
    NOT_RUN,
}

/** One check of the capability probe, with what it found and, when timed, how long it took. */
@InternalZakadiApi
data class ProbeCheck(
    val name: String,
    val outcome: CheckOutcome,
    val detail: String,
    val durationNanos: Long? = null,
)

/** What the capability probe of spec 07 section 7.26 found on one build of one device. */
@InternalZakadiApi
data class ProbeReport(
    /** `Build.FINGERPRINT`. */
    val fingerprint: String,
    /** `Build.VERSION.SDK_INT`. */
    val sdkInt: Int,
    /** [CapabilityProbe.VERSION] of the probe that ran. */
    val probeVersion: Int,
    /** Every check, in the order of spec 07 section 7.26. */
    val checks: List<ProbeCheck>,
    val facts: DeviceFacts,
)

/**
 * The runtime capability probe of spec 07 section 7.26: the AVC encoder with a real surface-mode
 * configure, start and stop at 480x640 (timed), its CBR and Constrained Baseline support, the Opus
 * self-test (not run until spec 07 section 7.20 is built), the low-RAM flag and total memory, the
 * front camera's hardware level, an `EGL_RECORDABLE_ANDROID` config, and the front camera. Each
 * check is reported, and the result is cached per `Build.FINGERPRINT`, `Build.VERSION.SDK_INT` and
 * [VERSION].
 */
@InternalZakadiApi
class CapabilityProbe(
    context: Context,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val context = context.applicationContext
    private val cache = ProbeCache(this.context)

    /** The cached report for this build, or a new run, which is then cached. */
    @WorkerThread
    fun cachedOrRun(): ProbeReport {
        val key = ProbeCache.key(Build.FINGERPRINT, Build.VERSION.SDK_INT, VERSION)
        return cache.read(key) ?: run().also { cache.write(key, it) }
    }

    /** Runs every check; the encoder check takes some 50 to 150 ms, so never on the main thread. */
    @WorkerThread
    fun run(): ProbeReport {
        val checks = ArrayList<ProbeCheck>()
        val encoder = pickAvcEncoder(preferSoftware = false)
        val encoderCheck = checkEncoder(encoder)
        checks += encoderCheck
        val support = encoder?.let {
            AvcCodecSupport.of(it.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC))
        }
        checks +=
            if (support == null) ProbeCheck(CBR, CheckOutcome.NOT_RUN, "no hardware AVC encoder")
            else outcome(CBR, support.cbr, if (support.cbr) "CBR listed" else "VBR only")
        checks +=
            if (support == null) {
                ProbeCheck(CONSTRAINED_BASELINE, CheckOutcome.NOT_RUN, "no hardware AVC encoder")
            } else {
                outcome(
                    CONSTRAINED_BASELINE,
                    support.constrainedBaseline,
                    if (support.constrainedBaseline) "listed" else "Baseline only (D5)",
                )
            }
        checks += ProbeCheck(OPUS, CheckOutcome.NOT_RUN, "waits for spec 07 section 7.20")
        val activity = context.getSystemService(ActivityManager::class.java)
        val lowRam = activity.isLowRamDevice
        checks += outcome(LOW_RAM, !lowRam, "isLowRamDevice $lowRam")
        val memory = ActivityManager.MemoryInfo().also { activity.getMemoryInfo(it) }
        checks +=
            outcome(TOTAL_MEM, memory.totalMem >= LOW_MEMORY_BYTES, "${memory.totalMem} bytes")
        val camera = frontCamera()
        checks +=
            if (camera == null) {
                ProbeCheck(CAMERA_LEVEL, CheckOutcome.NOT_RUN, "no front camera")
            } else {
                outcome(CAMERA_LEVEL, camera != LEVEL_LEGACY, "hardware level ${levelName(camera)}")
            }
        val egl = checkEgl()
        checks += egl
        checks += outcome(FRONT_CAMERA, camera != null, if (camera != null) "present" else "absent")
        val facts =
            DeviceFacts(
                frontCamera = camera != null,
                avcEncoder = encoderCheck.outcome == CheckOutcome.PASSED,
                lowRamDevice = lowRam,
                totalMemBytes = memory.totalMem,
                legacyCamera = camera == LEVEL_LEGACY,
                eglRecordable = egl.outcome == CheckOutcome.PASSED,
            )
        return ProbeReport(Build.FINGERPRINT, Build.VERSION.SDK_INT, VERSION, checks, facts)
    }

    /** The encoder check of [info], timed from its creation to its release. */
    internal fun checkEncoder(info: MediaCodecInfo?): ProbeCheck {
        if (info == null) {
            return ProbeCheck(AVC_ENCODER, CheckOutcome.FAILED, "no hardware AVC encoder")
        }
        val thread = HandlerThread("lv-probe").apply { start() }
        val begun = clock()
        return try {
            val spec = AvcFormatSpec(480, 640, 15, 400_000, 2000, surfaceInput = true)
            val encoder =
                AvcEncoder.create(info, spec, Handler(thread.looper), object : EncoderListener {})
            val stopped = CountDownLatch(1)
            encoder.stop(timeoutMs = 0) { stopped.countDown() }
            stopped.await(STOP_WAIT_MS, TimeUnit.MILLISECONDS)
            ProbeCheck(
                AVC_ENCODER,
                CheckOutcome.PASSED,
                "${info.name} step ${encoder.step}",
                clock() - begun,
            )
        } catch (e: Exception) {
            ProbeCheck(AVC_ENCODER, CheckOutcome.FAILED, "${info.name}: $e", clock() - begun)
        } finally {
            thread.quitSafely()
        }
    }

    private fun checkEgl(): ProbeCheck =
        try {
            EglCore().release()
            ProbeCheck(EGL_RECORDABLE, CheckOutcome.PASSED, "config created")
        } catch (e: RuntimeException) {
            ProbeCheck(EGL_RECORDABLE, CheckOutcome.FAILED, e.toString())
        }

    /** The hardware level of the first front camera, or null without one. */
    private fun frontCamera(): Int? {
        val manager = context.getSystemService(CameraManager::class.java) ?: return null
        return try {
            manager.cameraIdList
                .map { manager.getCameraCharacteristics(it) }
                .firstOrNull {
                    it.get(CameraCharacteristics.LENS_FACING) ==
                        CameraCharacteristics.LENS_FACING_FRONT
                }
                ?.let {
                    it.get(CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL) ?: LEVEL_LEGACY
                }
        } catch (_: Exception) {
            null
        }
    }

    private fun outcome(name: String, passed: Boolean, detail: String) =
        ProbeCheck(name, if (passed) CheckOutcome.PASSED else CheckOutcome.FAILED, detail)

    private fun levelName(level: Int): String =
        when (level) {
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY -> "LEGACY"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LIMITED -> "LIMITED"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_FULL -> "FULL"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_3 -> "3"
            CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_EXTERNAL -> "EXTERNAL"
            else -> level.toString()
        }

    companion object {
        /** The version of the checks; a change re-runs the probe on every device. */
        const val VERSION: Int = 1

        const val AVC_ENCODER: String = "avc_encoder"
        const val CBR: String = "cbr"
        const val CONSTRAINED_BASELINE: String = "constrained_baseline"
        const val OPUS: String = "opus_self_test"
        const val LOW_RAM: String = "low_ram"
        const val TOTAL_MEM: String = "total_mem"
        const val CAMERA_LEVEL: String = "camera_hardware_level"
        const val EGL_RECORDABLE: String = "egl_recordable"
        const val FRONT_CAMERA: String = "front_camera"

        private const val LEVEL_LEGACY = CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL_LEGACY
        private const val STOP_WAIT_MS = 1000L
    }
}
