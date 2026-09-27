@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import android.media.MediaCodecInfo
import android.media.MediaCodecList
import android.media.MediaFormat
import android.os.Build
import dev.zakadi.sdk.InternalZakadiApi

/**
 * What spec 07 section 7.19 reads of a codec to choose the AVC encoder. The flags [softwareOnly],
 * [hardwareAccelerated], [alias] and [vendor] are the API 29 ones and count only there.
 */
@InternalZakadiApi
class EncoderCandidate(
    val name: String,
    val isEncoder: Boolean,
    val mimeTypes: List<String>,
    val softwareOnly: Boolean = false,
    val hardwareAccelerated: Boolean = false,
    val alias: Boolean = false,
    val vendor: Boolean = false,
)

/** Software codec name prefixes below API 29 (spec 07 section 7.19). */
private val SOFTWARE_PREFIXES =
    listOf("OMX.google.", "c2.android.", "c2.google.", "OMX.ffmpeg.", "c2.ffmpeg.")

/** The prefixes of the platform's own software codecs. */
private val PLATFORM_PREFIXES = listOf("OMX.google.", "c2.android.", "c2.google.")

/**
 * Whether a codec counts as software: on API 29 and later when it is software-only, not
 * hardware-accelerated or an alias; below API 29 by its name ([isSoftwareByName]).
 */
@InternalZakadiApi
fun isSoftwareEncoder(candidate: EncoderCandidate, sdkInt: Int): Boolean =
    if (sdkInt >= 29) {
        candidate.softwareOnly || !candidate.hardwareAccelerated || candidate.alias
    } else {
        isSoftwareByName(candidate.name)
    }

/**
 * The name rule below API 29 (spec 07 section 7.19, D86): a name starting with a software prefix,
 * containing `.sw.`, or starting with neither `OMX.` nor `c2.` (AOSP `isSoftwareCodec`), ignoring
 * case.
 */
@InternalZakadiApi
fun isSoftwareByName(name: String): Boolean =
    SOFTWARE_PREFIXES.any { name.startsWith(it, ignoreCase = true) } ||
        name.contains(".sw.", ignoreCase = true) ||
        !(name.startsWith("OMX.", ignoreCase = true) || name.startsWith("c2.", ignoreCase = true))

/**
 * The AVC encoder of spec 07 section 7.19 among [candidates], in their platform order: hardware
 * encoders only, vendor codecs first on API 29 and later; software encoders only when
 * [preferSoftware] (a `device_quirks` setting, D45), the platform's own first. Null means no usable
 * encoder: `unsupported_device`.
 */
@InternalZakadiApi
fun pickAvcEncoder(
    candidates: List<EncoderCandidate>,
    preferSoftware: Boolean,
    sdkInt: Int,
): EncoderCandidate? {
    val order: Comparator<EncoderCandidate> =
        if (preferSoftware) {
            if (sdkInt >= 29) compareBy<EncoderCandidate> { it.vendor }.thenBy { it.alias }
            else compareByDescending { c -> PLATFORM_PREFIXES.any { c.name.startsWith(it, true) } }
        } else {
            compareByDescending { sdkInt >= 29 && it.vendor }
        }
    return candidates
        .filter { c ->
            c.isEncoder && c.mimeTypes.any { it.equals(MediaFormat.MIMETYPE_VIDEO_AVC, true) }
        }
        .filter { isSoftwareEncoder(it, sdkInt) == preferSoftware }
        .sortedWith(order)
        .firstOrNull()
}

/** [pickAvcEncoder] over this device's `REGULAR_CODECS`. */
@InternalZakadiApi
fun pickAvcEncoder(preferSoftware: Boolean): MediaCodecInfo? {
    val infos = MediaCodecList(MediaCodecList.REGULAR_CODECS).codecInfos
    val candidates = infos.map { it.toCandidate() }
    val chosen = pickAvcEncoder(candidates, preferSoftware, Build.VERSION.SDK_INT) ?: return null
    return infos[candidates.indexOf(chosen)]
}

/** The fields of [MediaCodecInfo] that [pickAvcEncoder] reads. */
@InternalZakadiApi
fun MediaCodecInfo.toCandidate(): EncoderCandidate =
    if (Build.VERSION.SDK_INT >= 29) {
        EncoderCandidate(
            name,
            isEncoder,
            supportedTypes.toList(),
            softwareOnly = isSoftwareOnly,
            hardwareAccelerated = isHardwareAccelerated,
            alias = isAlias,
            vendor = isVendor,
        )
    } else {
        EncoderCandidate(name, isEncoder, supportedTypes.toList())
    }
