@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.EncoderListener

/**
 * What a [CapturePipeline] reports, besides the encoder events of [EncoderListener]: every frame
 * captured, submitted or dropped, the rung changes, the camera, the media clock, the exposure and
 * the `config.video` of each encoder. Each event is timed by `SystemClock.elapsedRealtimeNanos()`
 * in `atNanos`. Called on the pipeline's threads (`lv-gl` for frames, `lv-venc` for the encoder,
 * main for the camera); implementations return quickly.
 */
@InternalZakadiApi
interface CaptureListener : EncoderListener {
    /** A camera frame captured, submitted to the encoder, or dropped before it. */
    fun onFrame(event: FrameEvent) {}

    /** A rung applied: at start and on each change. */
    fun onRung(event: RungEvent) {}

    /** The camera bound, or its surface or image size became known. */
    fun onCamera(event: CameraEvent) {}

    /** t0 of the media clock and the camera clock's domain (spec 07 section 7.5). */
    fun onClock(event: ClockEvent) {}

    /** The auto-exposure metering and the exposure compensation applied (7.18). */
    fun onExposure(event: ExposureEvent) {}

    /** The `config.video` of an encoder, once its first SPS gives the codec string (01 1.4). */
    fun onVideoConfig(event: VideoConfig) {}

    /** Path A failed and the pipeline moved to path B. */
    fun onPathFallback(event: PathFallback) {}

    /** The pipeline cannot go on, or a step of it failed. */
    fun onError(event: CaptureError) {}
}

/** Why a frame did not reach the encoder: each is a pre-encode drop (spec 07 section 7.7). */
@InternalZakadiApi
enum class DropReason {
    /** Before its slot at the paced rate. */
    PACING,

    /** Every 4th (level 1) or 2nd (level 2) paced frame. */
    DECIMATION,

    /** No encoder attached: starting, re-creating the encoder for a new size, or stopping. */
    NO_ENCODER,

    /** Path B: the encoder had no free input buffer. */
    ENCODER_BUSY,

    /** Path A: CameraX has not yet given the surface's transformation. */
    NO_TRANSFORM,

    /** Path B: the encoder offers no image for its input; it is being re-created. */
    INPUT_FORMAT,

    /** Drawing into or queueing to the encoder failed. */
    SUBMIT_FAILED,
}

/** A camera frame event. */
@InternalZakadiApi
data class FrameEvent(
    val atNanos: Long,
    val kind: Kind,
    /** The camera timestamp in nanoseconds, on the camera clock. */
    val timestampNanos: Long,
    /** `pts_ms` on the media clock (spec 07 section 7.5). */
    val ptsMs: Long,
    /** The rung the frame was paced for. */
    val rung: Int,
    /** For [Kind.DROPPED]. */
    val reason: DropReason? = null,
    /** For [Kind.SUBMITTED]: the encoder it went to. */
    val encoderId: Int? = null,
) {
    @InternalZakadiApi
    enum class Kind {
        CAPTURED,
        SUBMITTED,
        DROPPED,
    }
}

/** A rung applied. */
@InternalZakadiApi
data class RungEvent(
    val atNanos: Long,
    /** The rung asked for. */
    val requested: Int,
    /** The rung applied: [requested], or the best rung allowed when it asks for more (7.26). */
    val applied: Int,
    val width: Int,
    val height: Int,
    /** The paced rate: the rung's, capped by `max_fps`. */
    val fps: Int,
    val videoKbps: Int,
    /** Whether the size changed, so the encoder is re-created. */
    val recreatesEncoder: Boolean,
)

/** The camera as bound. */
@InternalZakadiApi
data class CameraEvent(
    val atNanos: Long,
    val path: CapturePath,
    /** Path A: the `SurfaceRequest` resolution; path B: the (rotated) image size. */
    val width: Int,
    val height: Int,
    /** The clockwise rotation applied to make frames upright: `config.video.rotation`. */
    val rotationDegrees: Int,
    val sensorRotationDegrees: Int,
    /** Path A: `TransformationInfo.hasCameraTransform()`; path B: false. */
    val hasCameraTransform: Boolean,
    /** `SENSOR_INFO_TIMESTAMP_SOURCE`, or null when unreported. */
    val timestampSource: Int?,
    /** Whether a preview for a `PreviewView` is bound too. */
    val preview: Boolean,
)

/** t0 of the media clock (spec 07 section 7.5). */
@InternalZakadiApi
data class ClockEvent(
    val atNanos: Long,
    /** The camera timestamp that became t0. */
    val t0Nanos: Long,
    val domain: CameraClockDomain,
    val timestampSource: Int?,
)

/** The exposure settings of spec 07 section 7.18 as applied. */
@InternalZakadiApi
data class ExposureEvent(
    val atNanos: Long,
    /** Whether the camera supports AE metering at the point, so the action was started. */
    val meteringStarted: Boolean,
    /** Always false: the action is built with `disableAutoCancel()`. */
    val autoCancel: Boolean,
    /** The metering point, in the coordinates of its factory. */
    val pointX: Float,
    val pointY: Float,
    /** Whether the camera supports exposure compensation. */
    val compensationSupported: Boolean,
    /** The index set: round(0.3 / step) clamped to [range]. */
    val index: Int? = null,
    val step: Double? = null,
    val range: IntRange? = null,
    /** `index * step`: the EV applied, for `camera_meta`. */
    val ev: Double? = null,
    /**
     * Whether the camera confirmed the index within 3 s: a capture result carried it with
     * auto-exposure settled. False when it did not, or when it refused it.
     */
    val applied: Boolean = false,
)

/** The `config.video` fields of one encoder (spec 01 section 1.4). */
@InternalZakadiApi
data class VideoConfig(
    val atNanos: Long,
    val encoderId: Int,
    /** From the first SPS, as `avc1.42E01F`. */
    val codec: String,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateKbps: Int,
    val gopMs: Int,
    /** Always false: frames go unmirrored. */
    val mirrored: Boolean,
    /** The clockwise rotation already applied. */
    val rotation: Int,
    val annexb: Boolean = true,
)

/** A move from path A to path B (spec 07 section 7.18). */
@InternalZakadiApi data class PathFallback(val atNanos: Long, val reason: String)

/** A pipeline failure. */
@InternalZakadiApi
data class CaptureError(
    val atNanos: Long,
    val kind: Kind,
    val message: String,
) {
    @InternalZakadiApi
    enum class Kind {
        /** No AVC encoder of the preference: `unsupported_device` (7.19). */
        NO_ENCODER,

        /** The encoder refused every format or failed to start. */
        ENCODER,

        /** No front camera, or binding it failed. */
        CAMERA,

        /** Path B failed too. */
        CAPTURE,
    }
}
