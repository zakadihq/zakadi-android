@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.device

import android.os.Build
import dev.zakadi.sdk.InternalZakadiApi

/**
 * One `device_quirks` entry of `GET /v1/sdk/config` (spec 02 section 2.6, D38): the keys it matches
 * on and the caps it sets. A null key is absent.
 */
@InternalZakadiApi
data class DeviceQuirk(
    /** `match.platform`. */
    val platform: String? = null,
    /** `match.model_prefix`: a prefix of `Build.MANUFACTURER + " " + Build.MODEL`. */
    val modelPrefix: String? = null,
    /** `match.browser`, which no native device matches. */
    val browser: String? = null,
    val browserMajorMin: Int? = null,
    val browserMajorMax: Int? = null,
    /** `match.os_major_min` and `match.os_major_max`: the Android major version. */
    val osMajorMin: Int? = null,
    val osMajorMax: Int? = null,
    /** `caps.max_fps`. */
    val maxFps: Int? = null,
    /** `caps.max_rung`: the best rung allowed. */
    val maxRung: Int? = null,
    /** `caps.prefer_software_encoder`. */
    val preferSoftwareEncoder: Boolean? = null,
)

/** The device as quirks match it (spec 07 section 7.26). */
@InternalZakadiApi
data class DeviceIdentity(val manufacturer: String, val model: String, val osMajor: Int) {
    /** `Build.MANUFACTURER + " " + Build.MODEL`, which `model_prefix` matches. */
    val modelName: String
        get() = "$manufacturer $model"

    companion object {
        /** This device. */
        fun current(): DeviceIdentity =
            DeviceIdentity(Build.MANUFACTURER, Build.MODEL, osMajor(Build.VERSION.RELEASE))

        /** The major version of an Android release string such as "8.1.0"; 0 when it has none. */
        fun osMajor(release: String): Int = release.takeWhile { it.isDigit() }.toIntOrNull() ?: 0
    }
}

/**
 * Whether [device] matches this entry: every key present must match (spec 02 section 2.6).
 * `platform` must be `android`, `model_prefix` must start the model name (ignoring case), the major
 * version must lie within `os_major_min` and `os_major_max`, and an entry with a browser key
 * matches no native device.
 */
@InternalZakadiApi
fun DeviceQuirk.matches(device: DeviceIdentity): Boolean =
    (platform == null || platform == "android") &&
        browser == null &&
        browserMajorMin == null &&
        browserMajorMax == null &&
        (modelPrefix == null || device.modelName.startsWith(modelPrefix, ignoreCase = true)) &&
        (osMajorMin == null || device.osMajor >= osMajorMin) &&
        (osMajorMax == null || device.osMajor <= osMajorMax)
