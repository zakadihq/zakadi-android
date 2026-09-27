@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import dev.zakadi.sdk.InternalZakadiApi

/**
 * What an [AvcEncoder] reports, each event timed by `SystemClock.elapsedRealtimeNanos()` in
 * `atNanos`. Called on the encoder's thread (`lv-venc`) or, for [onEncoderStarted] and the format
 * events of a configuration, on the thread that creates the encoder; implementations return
 * quickly.
 */
@InternalZakadiApi
interface EncoderListener {
    /** A format asked for, refused, or read back from the codec. */
    fun onFormat(event: FormatEvent) {}

    /** An encoder started after its configuration was accepted. */
    fun onEncoderStarted(event: EncoderStarted) {}

    /** An encoder released, drained to its end of stream or not. */
    fun onEncoderStopped(event: EncoderStopped) {}

    /** Every output buffer, sent or not. */
    fun onOutputBuffer(event: OutputBuffer) {}

    /** The SPS or the PPS changed. */
    fun onParameterSets(event: ParameterSetsChanged) {}

    /** A keyframe request, its repeat, or the IDR that answered it. */
    fun onKeyframe(event: KeyframeEvent) {}

    /** A bitrate request and the value applied after clamping. */
    fun onBitrate(event: BitrateEvent) {}

    /** A codec error or a refused request. */
    fun onEncoderError(event: EncoderError) {}
}

/** A format event of spec 07 section 7.19; [values] are the keys and their values as text. */
@InternalZakadiApi
data class FormatEvent(
    val atNanos: Long,
    val encoderId: Int,
    val kind: Kind,
    /** The refusal step (0 to 3) for [Kind.ASKED] and [Kind.REFUSED], otherwise the one taken. */
    val step: Int,
    val values: Map<String, String>,
    /** Why the codec refused the format, for [Kind.REFUSED]. */
    val error: String? = null,
) {
    @InternalZakadiApi
    enum class Kind {
        /** The keys given to `configure()`. */
        ASKED,

        /** `configure()` or `start()` threw; the next step drops keys. */
        REFUSED,

        /** `getInputFormat()` after `configure()`. */
        INPUT_READ_BACK,

        /** `getOutputFormat()` after `configure()`. */
        OUTPUT_READ_BACK,

        /** `onOutputFormatChanged`. */
        OUTPUT_CHANGED,
    }
}

/** An encoder that accepted a configuration and started. */
@InternalZakadiApi
data class EncoderStarted(
    val atNanos: Long,
    val encoderId: Int,
    val codecName: String,
    /** Whether it counts as hardware under the rule of spec 07 section 7.19: `hw_encode`. */
    val hardware: Boolean,
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val gopMs: Int,
    val surfaceInput: Boolean,
    /** The refusal step whose keys were accepted. */
    val step: Int,
    /** The bitrate range of the codec, which requests are clamped to. */
    val bitrateRange: IntRange,
    /** Nanoseconds from the first `createByCodecName` to `start()` returning. */
    val setupNanos: Long,
)

/** An encoder released. */
@InternalZakadiApi
data class EncoderStopped(
    val atNanos: Long,
    val encoderId: Int,
    /** Whether its end-of-stream buffer came out before the release. */
    val drained: Boolean,
)

/** One output buffer of spec 07 section 7.19. */
@InternalZakadiApi
class OutputBuffer(
    val atNanos: Long,
    val encoderId: Int,
    /** `BufferInfo.presentationTimeUs`: the camera timestamp in microseconds. */
    val presentationTimeUs: Long,
    /** `pts_ms` on the session media clock (spec 07 section 7.5). */
    val ptsMs: Long,
    /** `BufferInfo.size`. */
    val size: Int,
    /** `BufferInfo.flags`. */
    val flags: Int,
    /** `BUFFER_FLAG_KEY_FRAME`, advisory. */
    val keyFlag: Boolean,
    /** `BUFFER_FLAG_CODEC_CONFIG`. */
    val codecConfig: Boolean,
    /** `BUFFER_FLAG_END_OF_STREAM`. */
    val endOfStream: Boolean,
    /** The NAL unit types in the buffer, in order. */
    val nalTypes: List<Int>,
    /** Whether the buffer holds an IDR slice. */
    val idr: Boolean,
    /** The access unit to send, or null when the buffer carries no slice. */
    val accessUnit: ByteArray?,
    /** Whether [accessUnit] carries the SPS and the PPS: the `param_sets` bit. */
    val paramSets: Boolean,
)

/** The SPS or the PPS of an encoder changed. */
@InternalZakadiApi
class ParameterSetsChanged(
    val atNanos: Long,
    val encoderId: Int,
    val sps: ByteArray?,
    val pps: ByteArray?,
    /** `config.video.codec` from the encoder's first SPS. */
    val codecString: String?,
    /** Whether they came from the output format's `csd-0` and `csd-1` rather than a buffer. */
    val fromFormat: Boolean,
)

/** A keyframe request event of spec 07 section 7.19. */
@InternalZakadiApi
data class KeyframeEvent(
    val atNanos: Long,
    val encoderId: Int,
    val kind: Kind,
    /** For [Kind.ANSWERED]: milliseconds from the request to the IDR, `ms_to_idr`. */
    val msToIdr: Long? = null,
    /** For [Kind.ANSWERED]: frames submitted between the request and the IDR. */
    val framesToIdr: Int? = null,
) {
    @InternalZakadiApi
    enum class Kind {
        /** `PARAMETER_KEY_REQUEST_SYNC_FRAME` set. */
        REQUESTED,

        /** Set again: no IDR within 500 ms. */
        REPEATED,

        /** An IDR came out. */
        ANSWERED,
    }
}

/** A bitrate request: `PARAMETER_KEY_VIDEO_BITRATE` set to [appliedBps]. */
@InternalZakadiApi
data class BitrateEvent(
    val atNanos: Long,
    val encoderId: Int,
    val requestedBps: Int,
    /** [requestedBps] clamped to [range]. */
    val appliedBps: Int,
    val range: IntRange,
)

/** A codec error, or a request the codec refused. */
@InternalZakadiApi
data class EncoderError(
    val atNanos: Long,
    val encoderId: Int,
    val message: String,
    /** `CodecException.isRecoverable()` or `isTransient()`, when the codec says so. */
    val recoverable: Boolean,
)
