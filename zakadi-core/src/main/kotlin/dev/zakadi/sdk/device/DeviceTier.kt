@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CapturePath
import dev.zakadi.sdk.capture.EncoderPreference

/** The device tiers of spec 07 section 7.26. */
@InternalZakadiApi
enum class DeviceTier {
    /** No front camera or no usable AVC encoder: `unsupported_device`. */
    U,

    /** Low-RAM flag, under 2 GB, or a `LEGACY` camera: best rung 2, 15 fps, static character. */
    L,

    /** Otherwise: the full ladder on path A. */
    S,
}

/** What the capability probe found that the tiers read (spec 07 section 7.26). */
@InternalZakadiApi
data class DeviceFacts(
    val frontCamera: Boolean,
    /** A hardware AVC encoder passed the surface-mode configure, start and stop. */
    val avcEncoder: Boolean,
    /** `ActivityManager.isLowRamDevice()`. */
    val lowRamDevice: Boolean,
    /** `ActivityManager.MemoryInfo.totalMem`. */
    val totalMemBytes: Long,
    /** The front camera's hardware level is `LEGACY`. */
    val legacyCamera: Boolean,
    /** An EGL config with `EGL_RECORDABLE_ANDROID` was created. */
    val eglRecordable: Boolean,
)

/** What a device may do: its tier, tightened by the quirks that match it. */
@InternalZakadiApi
data class DeviceCaps(
    val tier: DeviceTier,
    /** The best rung allowed; rung 0 is the best of the ladder. */
    val bestRung: Int,
    /** The frame rate cap, or null for none. */
    val maxFps: Int?,
    /** Path A, or path B when the EGL check failed; null on tier U. */
    val path: CapturePath?,
    val encoder: EncoderPreference,
    /** Tier L shows the character without animation. */
    val staticCharacter: Boolean,
)

/** "Under 2 GB" of spec 07 section 7.26, read as under 2 GiB of `totalMem`. */
@InternalZakadiApi const val LOW_MEMORY_BYTES: Long = 2L * 1024 * 1024 * 1024

/**
 * The tier of spec 07 section 7.26 and what it allows. U: no front camera or no usable AVC encoder.
 * L: the low-RAM flag, under 2 GiB, or a `LEGACY` camera: best rung 2, 15 fps, the static
 * character. S: the full ladder. Path B replaces path A wherever the EGL check failed.
 */
@InternalZakadiApi
fun deviceCaps(facts: DeviceFacts): DeviceCaps {
    val path = if (facts.eglRecordable) CapturePath.A else CapturePath.B
    return when {
        !facts.frontCamera || !facts.avcEncoder ->
            DeviceCaps(DeviceTier.U, 0, null, null, EncoderPreference.HARDWARE, false)
        facts.lowRamDevice || facts.totalMemBytes < LOW_MEMORY_BYTES || facts.legacyCamera ->
            DeviceCaps(DeviceTier.L, 2, 15, path, EncoderPreference.HARDWARE, true)
        else -> DeviceCaps(DeviceTier.S, 0, null, path, EncoderPreference.HARDWARE, false)
    }
}

/**
 * These caps tightened by every quirk of [quirks] that matches [device] (spec 07 section 7.26, spec
 * 02 section 2.6): `max_rung` can only move the best rung down the ladder, `max_fps` can only lower
 * the cap, and `prefer_software_encoder` selects the software encoder. A quirk never loosens a cap,
 * and tier U stays U.
 */
@InternalZakadiApi
fun DeviceCaps.tightenedBy(quirks: List<DeviceQuirk>, device: DeviceIdentity): DeviceCaps {
    if (tier == DeviceTier.U) return this
    var caps = this
    for (quirk in quirks) {
        if (!quirk.matches(device)) continue
        caps =
            caps.copy(
                bestRung = maxOf(caps.bestRung, quirk.maxRung ?: caps.bestRung),
                maxFps = listOfNotNull(caps.maxFps, quirk.maxFps).minOrNull(),
                encoder =
                    if (quirk.preferSoftwareEncoder == true) EncoderPreference.SOFTWARE
                    else caps.encoder,
            )
    }
    return caps
}
