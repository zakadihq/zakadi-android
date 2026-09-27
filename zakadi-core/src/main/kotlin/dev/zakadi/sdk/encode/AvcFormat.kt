@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import android.media.MediaCodecInfo.CodecCapabilities
import android.media.MediaCodecInfo.CodecProfileLevel
import android.media.MediaCodecInfo.EncoderCapabilities
import android.media.MediaFormat
import android.os.Build
import dev.zakadi.sdk.InternalZakadiApi

/** The size, rate, bitrate and GOP of one encoder configuration (spec 07 section 7.19). */
@InternalZakadiApi
class AvcFormatSpec(
    val width: Int,
    val height: Int,
    val fps: Int,
    val bitrateBps: Int,
    val gopMs: Int,
    /** Surface input (path A of spec 07 section 7.18) or YUV buffer input (path B). */
    val surfaceInput: Boolean,
    /** The colour format of buffer input; surface input always asks for `COLOR_FormatSurface`. */
    val colorFormat: Int = CodecCapabilities.COLOR_FormatYUV420Flexible,
)

/** What the chosen codec lists that the format depends on. */
@InternalZakadiApi
class AvcCodecSupport(
    /** `BITRATE_MODE_CBR` is listed. */
    val cbr: Boolean,
    /** Constrained Baseline is listed and selectable, which takes API 27. */
    val constrainedBaseline: Boolean,
    /** `KEY_MAX_B_FRAMES` exists, from API 29. */
    val maxBFramesKey: Boolean,
) {
    companion object {
        /**
         * The support of a codec that [cbrListed] CBR and lists [profiles], at API [sdkInt]:
         * Constrained Baseline counts from API 27, `KEY_MAX_B_FRAMES` from API 29.
         */
        fun of(cbrListed: Boolean, profiles: List<Int>, sdkInt: Int) =
            AvcCodecSupport(
                cbr = cbrListed,
                constrainedBaseline = sdkInt >= 27 && AVC_PROFILE_CONSTRAINED_BASELINE in profiles,
                maxBFramesKey = sdkInt >= 29,
            )

        /** The support [capabilities] list on this device. */
        fun of(capabilities: CodecCapabilities) =
            of(
                capabilities.encoderCapabilities?.isBitrateModeSupported(
                    EncoderCapabilities.BITRATE_MODE_CBR
                ) == true,
                capabilities.profileLevels.map { it.profile },
                Build.VERSION.SDK_INT,
            )
    }
}

/** [bps] clamped to the codec's bitrate [range] (spec 07 section 7.19). */
@InternalZakadiApi fun clampBitrate(bps: Int, range: IntRange): Int = bps.coerceIn(range)

/** `CodecProfileLevel.AVCProfileConstrainedBaseline`, a constant of API 27. */
@InternalZakadiApi const val AVC_PROFILE_CONSTRAINED_BASELINE: Int = 0x10000

/** `MediaFormat.KEY_MAX_B_FRAMES`, a key of API 29. */
@InternalZakadiApi const val KEY_MAX_B_FRAMES: String = "max-bframes"

/**
 * The keys each `configure()` refusal drops, cumulatively and in the order of spec 07 section 7.19:
 * nothing at first, then `KEY_LATENCY`, then also `KEY_PROFILE` and `KEY_LEVEL`, then also
 * `KEY_BITRATE_MODE`.
 */
@InternalZakadiApi
val FORMAT_REFUSAL_STEPS: List<Set<String>> =
    listOf(
        emptySet(),
        setOf(MediaFormat.KEY_LATENCY),
        setOf(MediaFormat.KEY_LATENCY, MediaFormat.KEY_PROFILE, MediaFormat.KEY_LEVEL),
        setOf(
            MediaFormat.KEY_LATENCY,
            MediaFormat.KEY_PROFILE,
            MediaFormat.KEY_LEVEL,
            MediaFormat.KEY_BITRATE_MODE,
        ),
    )

/**
 * The format keys spec 07 section 7.19 asks for at refusal step [step], besides the MIME type and
 * size: CBR where listed, else VBR; Constrained Baseline where selectable, else Baseline (D5);
 * level 3.1; `KEY_LATENCY` 1 and `KEY_PRIORITY` 0; no B-frames where the key exists. The I-frame
 * interval is a float of seconds.
 */
@InternalZakadiApi
fun avcFormatKeys(spec: AvcFormatSpec, support: AvcCodecSupport, step: Int): Map<String, Number> {
    val keys = LinkedHashMap<String, Number>()
    keys[MediaFormat.KEY_COLOR_FORMAT] =
        if (spec.surfaceInput) CodecCapabilities.COLOR_FormatSurface else spec.colorFormat
    keys[MediaFormat.KEY_BIT_RATE] = spec.bitrateBps
    keys[MediaFormat.KEY_FRAME_RATE] = spec.fps
    keys[MediaFormat.KEY_I_FRAME_INTERVAL] = spec.gopMs / 1000f
    keys[MediaFormat.KEY_BITRATE_MODE] =
        if (support.cbr) EncoderCapabilities.BITRATE_MODE_CBR
        else EncoderCapabilities.BITRATE_MODE_VBR
    keys[MediaFormat.KEY_PROFILE] =
        if (support.constrainedBaseline) AVC_PROFILE_CONSTRAINED_BASELINE
        else CodecProfileLevel.AVCProfileBaseline
    keys[MediaFormat.KEY_LEVEL] = CodecProfileLevel.AVCLevel31
    keys[MediaFormat.KEY_LATENCY] = 1
    keys[MediaFormat.KEY_PRIORITY] = 0
    if (support.maxBFramesKey) keys[KEY_MAX_B_FRAMES] = 0
    FORMAT_REFUSAL_STEPS[step].forEach { keys.remove(it) }
    return keys
}

/** [keys] and the size of [spec] as a `video/avc` [MediaFormat]. */
internal fun avcMediaFormat(spec: AvcFormatSpec, keys: Map<String, Number>): MediaFormat =
    MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, spec.width, spec.height).apply {
        for ((key, value) in keys) {
            if (value is Float) setFloat(key, value) else setInteger(key, value.toInt())
        }
    }

/** The keys that are read back below API 29, where [MediaFormat] cannot list its keys. */
private val READ_BACK_KEYS =
    listOf(
        MediaFormat.KEY_MIME,
        MediaFormat.KEY_WIDTH,
        MediaFormat.KEY_HEIGHT,
        MediaFormat.KEY_COLOR_FORMAT,
        MediaFormat.KEY_BIT_RATE,
        MediaFormat.KEY_FRAME_RATE,
        MediaFormat.KEY_I_FRAME_INTERVAL,
        MediaFormat.KEY_BITRATE_MODE,
        MediaFormat.KEY_PROFILE,
        MediaFormat.KEY_LEVEL,
        MediaFormat.KEY_LATENCY,
        MediaFormat.KEY_PRIORITY,
        KEY_MAX_B_FRAMES,
        MediaFormat.KEY_STRIDE,
        MediaFormat.KEY_SLICE_HEIGHT,
        "csd-0",
        "csd-1",
    )

/** Every key of [format] with its value as text; byte buffers as lower-case hex. */
internal fun readFormat(format: MediaFormat): Map<String, String> {
    val keys = if (Build.VERSION.SDK_INT >= 29) format.keys.sorted() else READ_BACK_KEYS
    val values = LinkedHashMap<String, String>()
    for (key in keys) {
        if (!format.containsKey(key)) continue
        val value =
            try {
                format.getInteger(key).toString()
            } catch (_: ClassCastException) {
                readOther(format, key)
            }
        values[key] = value
    }
    return values
}

private fun readOther(format: MediaFormat, key: String): String =
    try {
        format.getLong(key).toString()
    } catch (_: ClassCastException) {
        try {
            format.getFloat(key).toString()
        } catch (_: ClassCastException) {
            try {
                format.getString(key) ?: ""
            } catch (_: ClassCastException) {
                format.getByteBuffer(key)?.let { hex(it) } ?: ""
            }
        }
    }

private fun hex(buffer: java.nio.ByteBuffer): String {
    val bytes = ByteArray(buffer.remaining())
    buffer.duplicate().get(bytes)
    return bytes.toHexString()
}
