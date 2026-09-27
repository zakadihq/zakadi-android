@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi

/**
 * One entry of `ready.ladder` (spec 01 section 1.5): the rung index, the encoded size, which is 3:4
 * and 16-aligned, the frame rate and the video bitrate. Rung 0 is the best.
 */
@InternalZakadiApi
data class Rung(val index: Int, val width: Int, val height: Int, val fps: Int, val videoKbps: Int)

/** Where the encoder's frames come from (spec 07 section 7.18). */
@InternalZakadiApi
enum class CapturePath {
    /** `VideoCapture` into an OpenGL ES pass that draws into the encoder's input surface. */
    A,

    /** `ImageAnalysis` YUV frames cropped and scaled into the encoder's input buffers. */
    B,
}

/** Which kind of AVC encoder the pipeline asks for (spec 07 section 7.19, D45). */
@InternalZakadiApi
enum class EncoderPreference {
    /** A hardware encoder, vendor codecs first: the default. */
    HARDWARE,

    /** The platform's own software encoder, only when `device_quirks.prefer_software_encoder`. */
    SOFTWARE,
}
