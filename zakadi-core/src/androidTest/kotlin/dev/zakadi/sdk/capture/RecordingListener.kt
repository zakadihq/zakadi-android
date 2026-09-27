@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.BitrateEvent
import dev.zakadi.sdk.encode.EncoderError
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.FormatEvent
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.ParameterSetsChanged
import java.util.concurrent.CopyOnWriteArrayList

/** Keeps every event of a pipeline or an encoder, in the order they came. */
class RecordingListener : CaptureListener {
    val events = CopyOnWriteArrayList<Any>()

    inline fun <reified T> all(): List<T> = events.filterIsInstance<T>()

    override fun onFormat(event: FormatEvent) {
        events += event
    }

    override fun onEncoderStarted(event: EncoderStarted) {
        events += event
    }

    override fun onEncoderStopped(event: EncoderStopped) {
        events += event
    }

    override fun onOutputBuffer(event: OutputBuffer) {
        events += event
    }

    override fun onParameterSets(event: ParameterSetsChanged) {
        events += event
    }

    override fun onKeyframe(event: KeyframeEvent) {
        events += event
    }

    override fun onBitrate(event: BitrateEvent) {
        events += event
    }

    override fun onEncoderError(event: EncoderError) {
        events += event
    }

    override fun onFrame(event: FrameEvent) {
        events += event
    }

    override fun onRung(event: RungEvent) {
        events += event
    }

    override fun onCamera(event: CameraEvent) {
        events += event
    }

    override fun onClock(event: ClockEvent) {
        events += event
    }

    override fun onExposure(event: ExposureEvent) {
        events += event
    }

    override fun onVideoConfig(event: VideoConfig) {
        events += event
    }

    override fun onPathFallback(event: PathFallback) {
        events += event
    }

    override fun onError(event: CaptureError) {
        events += event
    }
}
