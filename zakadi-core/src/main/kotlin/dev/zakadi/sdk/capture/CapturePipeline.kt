@file:kotlin.OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.content.Context
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CaptureRequest
import android.media.MediaCodecInfo
import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaFormat
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.os.SystemClock
import android.util.Range
import android.util.Size
import android.view.Surface
import androidx.annotation.OptIn
import androidx.camera.camera2.interop.Camera2CameraInfo
import androidx.camera.camera2.interop.Camera2Interop
import androidx.camera.camera2.interop.ExperimentalCamera2Interop
import androidx.camera.core.Camera
import androidx.camera.core.CameraInfo
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.MirrorMode
import androidx.camera.core.Preview
import androidx.camera.core.SurfaceOrientedMeteringPointFactory
import androidx.camera.core.UseCase
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.video.VideoCapture
import androidx.core.content.ContextCompat
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.AvcEncoder
import dev.zakadi.sdk.encode.AvcFormatSpec
import dev.zakadi.sdk.encode.BitrateEvent
import dev.zakadi.sdk.encode.EncoderError
import dev.zakadi.sdk.encode.EncoderListener
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.FormatEvent
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.ParameterSetsChanged
import dev.zakadi.sdk.encode.pickAvcEncoder
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicBoolean

/**
 * The capture pipeline of spec 07 sections 7.18 and 7.19 on the camera clock of 7.5: the front
 * camera through path A or B into an AVC encoder, paced to the rung. It starts at a rung with an
 * encoder preference and a path, changes rung (a new size re-creates the encoder; the frames in
 * between are pre-encode drops), takes decimation, keyframe and bitrate requests, and stops. It has
 * no sender: every frame, output buffer, parameter set change, request and format goes to
 * [listener], timed by [clock] (`SystemClock.elapsedRealtimeNanos()`).
 *
 * Threads (spec 07 section 7.2): camera binding on main, the frame path on `lv-gl`, the encoder on
 * `lv-venc`. Every method may be called from any thread. A pipeline starts once.
 */
@InternalZakadiApi
class CapturePipeline(
    context: Context,
    private val listener: CaptureListener,
    private val clock: () -> Long = SystemClock::elapsedRealtimeNanos,
) {
    private val context = context.applicationContext
    private val main = Handler(Looper.getMainLooper())
    private val mainExecutor = ContextCompat.getMainExecutor(context)
    private val glThread = HandlerThread("lv-gl")
    private val encThread = HandlerThread("lv-venc")
    private lateinit var gl: Handler
    private lateinit var enc: Handler
    private lateinit var glExecutor: Executor
    private lateinit var config: CaptureConfig
    private lateinit var encoderInfo: MediaCodecInfo
    private var bestRung = 0
    private val stats = CaptureStats()

    /** The session media clock: t0 is the first frame after [start]. */
    val mediaClock = MediaClock()

    @Volatile private var started = false
    @Volatile private var stopped = false
    @Volatile private var rotation = 0
    @Volatile private var sensorRotation = 0

    // lv-gl
    private var path = CapturePath.A
    private var glPath: GlFramePath? = null
    private var imagePath: ImageFramePath? = null
    private lateinit var rung: Rung
    private lateinit var pacer: Pacer
    private var decimation = 0
    private var attached: AvcEncoder? = null
    private var wantedSeq = 0
    private var pendingBitrate: Int? = null
    private var rawColorFormat: Int? = null
    private var timestampSource: Int? = null

    // lv-venc
    private var live: AvcEncoder? = null
    private var wanted: Want? = null
    private var draining = false
    private var shutdown: (() -> Unit)? = null
    private val configsSent = HashSet<Int>()

    // main
    private var provider: ProcessCameraProvider? = null
    private var camera: Camera? = null
    private val exposureDue = AtomicBoolean(false)
    private val lifecycle = PipelineLifecycle()
    private var useCases: List<UseCase> = emptyList()

    private class Want(val seq: Int, val rung: Rung, val spec: AvcFormatSpec)

    /**
     * Starts the camera and the encoder at [config]'s start rung. Without an AVC encoder of the
     * preference, or with no rung size the encoder supports, it reports a [CaptureError] and does
     * not start.
     */
    fun start(config: CaptureConfig) {
        check(!started) { "a pipeline starts once" }
        started = true
        this.config = config
        val info = pickAvcEncoder(config.encoder == EncoderPreference.SOFTWARE)
        if (info == null) {
            fail(CaptureError.Kind.NO_ENCODER, "no ${config.encoder} AVC encoder")
            stopped = true
            return
        }
        encoderInfo = info
        val video =
            checkNotNull(
                info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC).videoCapabilities
            )
        val best =
            bestSupportedRung(config.ladder, config.bestRung) {
                video.isSizeSupported(it.width, it.height)
            }
        if (best == null) {
            fail(CaptureError.Kind.ENCODER, "${info.name} supports no rung size of the ladder")
            stopped = true
            return
        }
        bestRung = best
        glThread.start()
        encThread.start()
        gl = Handler(glThread.looper)
        enc = Handler(encThread.looper)
        glExecutor = Executor { gl.post(it) }
        gl.post {
            path = config.path
            if (path == CapturePath.A) {
                try {
                    glPath = GlFramePath(gl, sink)
                } catch (e: RuntimeException) {
                    listener.onPathFallback(PathFallback(clock(), "EGL: $e"))
                    path = CapturePath.B
                }
            }
            if (path == CapturePath.B) imagePath = ImageFramePath(sink)
            val first = config.rung(appliedRung(config.ladder, config.startRung, bestRung))
            pacer = Pacer(config.pacedFps(first), decimation)
            rung = first
            report(config.startRung, recreates = true)
            requestEncoder()
            val a = glPath
            val b = imagePath
            main.post { bindCamera(a, b) }
        }
    }

    /** Moves to rung [index], applied at the best rung allowed when it asks for a better one. */
    fun setRung(index: Int) {
        if (!running()) return
        gl.post {
            if (stopped) return@post
            val next = config.rung(appliedRung(config.ladder, index, bestRung))
            val recreates = next.width != rung.width || next.height != rung.height
            rung = next
            pacer.configure(config.pacedFps(next), decimation)
            report(index, recreates)
            if (recreates) {
                pendingBitrate = null
                detachEncoder()
                requestEncoder()
            } else {
                bitrate(next.videoKbps * 1000)
            }
        }
    }

    /** Sets the decimation level: 0, or dropping every 4th (1) or 2nd (2) paced frame. */
    fun setDecimation(level: Int) {
        require(level in 0..2) { "decimation $level is not 0, 1 or 2" }
        if (!running()) return
        gl.post {
            decimation = level
            pacer.configure(pacer.fps, level)
        }
    }

    /** Asks the encoder for an IDR, repeated once when none comes within 500 ms. */
    fun requestKeyframe() {
        if (!running()) return
        gl.post { attached?.requestKeyframe() }
    }

    /**
     * Sets the encoder bitrate, clamped to the codec's range; kept for the next encoder if none.
     */
    fun requestBitrate(kbps: Int) {
        if (!running()) return
        gl.post { bitrate(kbps * 1000) }
    }

    /** The capture fields of `stats` (spec 07 section 7.7) now. */
    fun stats(): CaptureStatsSnapshot = stats.snapshot(clock())

    /**
     * Unbinds the camera, drains and releases the encoder and ends the pipeline's threads, then
     * calls [onStopped] on the encoder thread.
     */
    fun stop(onStopped: (() -> Unit)? = null) {
        if (!started || stopped || !::gl.isInitialized) {
            stopped = true
            onStopped?.invoke()
            return
        }
        stopped = true
        main.post {
            unbindCamera()
            lifecycle.destroy()
            gl.post {
                detachEncoder()
                glPath?.release()
                glPath = null
                imagePath = null
                enc.post {
                    wanted = null
                    shutdown = {
                        glThread.quitSafely()
                        encThread.quitSafely()
                        onStopped?.invoke()
                    }
                    advance()
                }
            }
        }
    }

    private fun running(): Boolean = started && !stopped && ::gl.isInitialized

    // lv-gl

    private fun report(requested: Int, recreates: Boolean) {
        listener.onRung(
            RungEvent(
                clock(),
                requested,
                rung.index,
                rung.width,
                rung.height,
                config.pacedFps(rung),
                rung.videoKbps,
                recreates,
            )
        )
    }

    private fun bitrate(bps: Int) {
        val target = attached
        if (target != null) target.requestBitrate(bps) else pendingBitrate = bps
    }

    private fun requestEncoder() {
        if (stopped) return
        val seq = ++wantedSeq
        val spec =
            AvcFormatSpec(
                width = rung.width,
                height = rung.height,
                fps = config.pacedFps(rung),
                bitrateBps = rung.videoKbps * 1000,
                gopMs = config.gopMs,
                surfaceInput = path == CapturePath.A,
                colorFormat = rawColorFormat ?: CodecCapabilities.COLOR_FormatYUV420Flexible,
            )
        val want = Want(seq, rung, spec)
        enc.post {
            wanted = want
            advance()
        }
    }

    private fun attach(encoder: AvcEncoder, seq: Int) {
        if (stopped || seq != wantedSeq) return
        try {
            when (path) {
                CapturePath.A -> checkNotNull(glPath).attach(encoder)
                CapturePath.B -> checkNotNull(imagePath).encoder = encoder
            }
        } catch (e: RuntimeException) {
            fallBack("EGL surface: $e")
            return
        }
        attached = encoder
        pacer.configure(config.pacedFps(rung), decimation)
        pendingBitrate?.let {
            pendingBitrate = null
            encoder.requestBitrate(it)
        }
    }

    private fun detachEncoder() {
        glPath?.detach()
        imagePath?.encoder = null
        attached = null
    }

    private fun fallBack(reason: String) {
        if (path == CapturePath.B || stopped) return
        listener.onPathFallback(PathFallback(clock(), reason))
        detachEncoder()
        glPath?.release()
        glPath = null
        path = CapturePath.B
        val b = ImageFramePath(sink)
        imagePath = b
        requestEncoder()
        main.post { bindCamera(null, b) }
    }

    private val sink =
        object : FrameSink {
            override fun onCaptured(timestampNanos: Long): Boolean {
                val now = clock()
                if (mediaClock.t0Nanos == null) {
                    mediaClock.onVideo(timestampNanos)
                    stats.restartDrops()
                    val domain =
                        cameraClockDomain(
                            timestampSource,
                            timestampNanos,
                            System.nanoTime(),
                            SystemClock.elapsedRealtimeNanos(),
                        )
                    listener.onClock(ClockEvent(now, timestampNanos, domain, timestampSource))
                }
                if (exposureDue.compareAndSet(true, false))
                    main.post { camera?.let(::applyExposure) }
                stats.onCaptured(now)
                val pts = mediaClock.ptsMs(timestampNanos)
                listener.onFrame(
                    FrameEvent(now, FrameEvent.Kind.CAPTURED, timestampNanos, pts, rung.index)
                )
                return when (pacer.decide(timestampNanos)) {
                    PaceDecision.KEEP -> true
                    PaceDecision.DROP_PACING -> {
                        onDropped(timestampNanos, DropReason.PACING)
                        false
                    }
                    PaceDecision.DROP_DECIMATION -> {
                        onDropped(timestampNanos, DropReason.DECIMATION)
                        false
                    }
                }
            }

            override fun onDropped(timestampNanos: Long, reason: DropReason) {
                stats.onDropped()
                listener.onFrame(
                    FrameEvent(
                        clock(),
                        FrameEvent.Kind.DROPPED,
                        timestampNanos,
                        mediaClock.ptsMs(timestampNanos),
                        rung.index,
                        reason,
                    )
                )
            }

            override fun onSubmitted(timestampNanos: Long, encoder: AvcEncoder) {
                stats.onSubmitted()
                listener.onFrame(
                    FrameEvent(
                        clock(),
                        FrameEvent.Kind.SUBMITTED,
                        timestampNanos,
                        mediaClock.ptsMs(timestampNanos),
                        rung.index,
                        encoderId = encoder.id,
                    )
                )
            }

            override fun onGeometry(
                width: Int,
                height: Int,
                rotationDegrees: Int,
                hasCameraTransform: Boolean,
            ) {
                rotation = rotationDegrees
                listener.onCamera(
                    CameraEvent(
                        clock(),
                        path,
                        width,
                        height,
                        rotationDegrees,
                        sensorRotation,
                        hasCameraTransform,
                        timestampSource,
                        config.previewSurfaceProvider != null,
                    )
                )
            }

            override fun onEglFailure(e: Exception) {
                fallBack("EGL: $e")
            }

            override fun onFrameFailure(timestampNanos: Long, e: RuntimeException) {
                fail(CaptureError.Kind.CAPTURE, "frame $timestampNanos: $e")
            }

            override fun onInputImageUnavailable(encoder: AvcEncoder) {
                if (rawColorFormat != null || encoder !== attached) return
                val formats = encoderInfo.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
                val raw = RawYuvLayout.pick(formats.colorFormats)
                if (raw == null) {
                    fail(CaptureError.Kind.CAPTURE, "${encoder.codecName}: no YUV input format")
                    return
                }
                rawColorFormat = raw
                detachEncoder()
                requestEncoder()
            }
        }

    // lv-venc

    private fun advance() {
        if (draining) return
        val current = live
        if (current != null) {
            if (wanted == null && shutdown == null) return
            draining = true
            current.stop {
                live = null
                draining = false
                advance()
            }
            return
        }
        val want = wanted
        if (want != null && shutdown == null) {
            wanted = null
            val encoder =
                try {
                    AvcEncoder.create(
                        encoderInfo,
                        want.spec,
                        enc,
                        encoderEvents,
                        ptsMsOf = { us -> mediaClock.ptsMs(us * 1000) },
                        clock = clock,
                    )
                } catch (e: RuntimeException) {
                    fail(CaptureError.Kind.ENCODER, "$e: ${e.cause}")
                    return
                }
            live = encoder
            gl.post { attach(encoder, want.seq) }
            return
        }
        shutdown?.let {
            shutdown = null
            it()
        }
    }

    private val encoderEvents =
        object : EncoderListener {
            override fun onFormat(event: FormatEvent) = listener.onFormat(event)

            override fun onEncoderStarted(event: EncoderStarted) = listener.onEncoderStarted(event)

            override fun onEncoderStopped(event: EncoderStopped) = listener.onEncoderStopped(event)

            override fun onOutputBuffer(event: OutputBuffer) {
                event.accessUnit?.let { stats.onAccessUnit(event.atNanos, it.size) }
                listener.onOutputBuffer(event)
            }

            override fun onParameterSets(event: ParameterSetsChanged) {
                listener.onParameterSets(event)
                val codec = event.codecString ?: return
                val encoder = live ?: return
                if (encoder.id != event.encoderId || !configsSent.add(encoder.id)) return
                val spec = encoder.spec
                listener.onVideoConfig(
                    VideoConfig(
                        atNanos = event.atNanos,
                        encoderId = encoder.id,
                        codec = codec,
                        width = spec.width,
                        height = spec.height,
                        fps = spec.fps,
                        bitrateKbps = spec.bitrateBps / 1000,
                        gopMs = spec.gopMs,
                        mirrored = false,
                        rotation = rotation,
                    )
                )
            }

            override fun onKeyframe(event: KeyframeEvent) = listener.onKeyframe(event)

            override fun onBitrate(event: BitrateEvent) = listener.onBitrate(event)

            override fun onEncoderError(event: EncoderError) = listener.onEncoderError(event)
        }

    // main

    private fun bindCamera(a: GlFramePath?, b: ImageFramePath?) {
        if (stopped) return
        val known = provider
        if (known != null) {
            bind(known, a, b)
            return
        }
        val future = ProcessCameraProvider.getInstance(context)
        future.addListener(
            {
                if (stopped) return@addListener
                val p =
                    try {
                        future.get()
                    } catch (e: Exception) {
                        fail(CaptureError.Kind.CAMERA, "CameraX: $e")
                        return@addListener
                    }
                provider = p
                bind(p, a, b)
            },
            mainExecutor,
        )
    }

    private fun bind(p: ProcessCameraProvider, a: GlFramePath?, b: ImageFramePath?) {
        val selector = CameraSelector.DEFAULT_FRONT_CAMERA
        val info =
            try {
                p.getCameraInfo(selector)
            } catch (e: IllegalArgumentException) {
                fail(CaptureError.Kind.CAMERA, "no front camera: $e")
                return
            }
        val facts =
            CameraFacts(info.sensorRotationDegrees, frontFacing = true, timestampSource(info))
        sensorRotation = facts.sensorRotationDegrees
        a?.camera = facts
        b?.rotationDegrees = info.getSensorRotationDegrees(Surface.ROTATION_0)
        gl.post { timestampSource = facts.timestampSource }
        val cases = ArrayList<UseCase>()
        config.previewSurfaceProvider?.let { cases += preview(it) }
        val cap = config.maxFps ?: 30
        if (a != null) {
            cases +=
                VideoCapture.Builder(a)
                    .setTargetFrameRate(Range(minOf(15, cap), cap))
                    .setMirrorMode(MirrorMode.MIRROR_MODE_OFF)
                    .setTargetRotation(Surface.ROTATION_0)
                    .build()
        } else if (b != null) {
            cases += analysis(b, info, cap)
        }
        unbindCamera()
        useCases = cases
        lifecycle.resume()
        val camera =
            try {
                p.bindToLifecycle(lifecycle, selector, *cases.toTypedArray())
            } catch (e: RuntimeException) {
                useCases = emptyList()
                if (a != null) gl.post { fallBack("bind: $e") }
                else fail(CaptureError.Kind.CAMERA, "bind: $e")
                return
            }
        this.camera = camera
        exposureDue.set(true)
    }

    private fun unbindCamera() {
        camera = null
        exposureDue.set(false)
        val p = provider ?: return
        if (useCases.isNotEmpty()) p.unbind(*useCases.toTypedArray())
        useCases = emptyList()
    }

    private fun preview(provider: Preview.SurfaceProvider): Preview =
        Preview.Builder()
            .setTargetFrameRate(Range(15, 30))
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .build()
            )
            .build()
            .also { it.setSurfaceProvider(provider) }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun analysis(b: ImageFramePath, info: CameraInfo, cap: Int): ImageAnalysis {
        val builder =
            ImageAnalysis.Builder()
                .setResolutionSelector(
                    ResolutionSelector.Builder()
                        .setResolutionStrategy(
                            ResolutionStrategy(
                                Size(640, 480),
                                ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER,
                            )
                        )
                        .build()
                )
                .setOutputImageFormat(ImageAnalysis.OUTPUT_IMAGE_FORMAT_YUV_420_888)
                .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                .setOutputImageRotationEnabled(true)
                .setTargetRotation(Surface.ROTATION_0)
        val ranges = info.supportedFrameRateRanges.map { it.lower..it.upper }
        aeTargetFpsRange(ranges, cap)?.let {
            Camera2Interop.Extender(builder)
                .setCaptureRequestOption(
                    CaptureRequest.CONTROL_AE_TARGET_FPS_RANGE,
                    Range(it.first, it.last),
                )
        }
        return builder.build().also { it.setAnalyzer(glExecutor, b) }
    }

    @OptIn(markerClass = [ExperimentalCamera2Interop::class])
    private fun timestampSource(info: CameraInfo): Int? =
        try {
            Camera2CameraInfo.from(info)
                .getCameraCharacteristic(CameraCharacteristics.SENSOR_INFO_TIMESTAMP_SOURCE)
        } catch (_: IllegalArgumentException) {
            null
        }

    private fun applyExposure(camera: Camera) {
        val factory = config.meteringPointFactory ?: SurfaceOrientedMeteringPointFactory(1f, 1f)
        val point = factory.createPoint(config.meteringX, config.meteringY)
        val action =
            FocusMeteringAction.Builder(point, FocusMeteringAction.FLAG_AE)
                .disableAutoCancel()
                .build()
        val metering = camera.cameraInfo.isFocusMeteringSupported(action)
        if (metering) camera.cameraControl.startFocusAndMetering(action)
        val exposure = camera.cameraInfo.exposureState
        if (!exposure.isExposureCompensationSupported) {
            listener.onExposure(
                ExposureEvent(
                    clock(),
                    metering,
                    action.isAutoCancelEnabled,
                    config.meteringX,
                    config.meteringY,
                    compensationSupported = false,
                )
            )
            return
        }
        val step = exposure.exposureCompensationStep.toDouble()
        val range = exposure.exposureCompensationRange.let { it.lower..it.upper }
        val index =
            exposureCompensationIndex(EXPOSURE_COMPENSATION_EV, step, range.first, range.last)
        // CameraX completes the future once auto-exposure converges on the new index, which some
        // cameras never report: the event goes once, at that point or after a timeout.
        val reported = AtomicBoolean(false)
        val report = { applied: Boolean ->
            if (reported.compareAndSet(false, true)) {
                listener.onExposure(
                    ExposureEvent(
                        clock(),
                        metering,
                        action.isAutoCancelEnabled,
                        config.meteringX,
                        config.meteringY,
                        compensationSupported = true,
                        index = index,
                        step = step,
                        range = range,
                        ev = index * step,
                        applied = applied,
                    )
                )
            }
        }
        val future = camera.cameraControl.setExposureCompensationIndex(index)
        future.addListener(
            {
                report(
                    try {
                        future.get()
                        true
                    } catch (_: Exception) {
                        false
                    }
                )
            },
            mainExecutor,
        )
        main.postDelayed({ report(false) }, EXPOSURE_CONFIRM_MS)
    }

    private companion object {
        /** How long the exposure event waits for the camera to confirm the index. */
        const val EXPOSURE_CONFIRM_MS = 3000L
    }

    private fun fail(kind: CaptureError.Kind, message: String) {
        listener.onError(CaptureError(clock(), kind, message))
    }
}
