@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.probe

import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.capture.CameraEvent
import dev.zakadi.sdk.capture.CaptureError
import dev.zakadi.sdk.capture.CaptureListener
import dev.zakadi.sdk.capture.ClockEvent
import dev.zakadi.sdk.capture.ExposureEvent
import dev.zakadi.sdk.capture.FrameEvent
import dev.zakadi.sdk.capture.PathFallback
import dev.zakadi.sdk.capture.RungEvent
import dev.zakadi.sdk.capture.VideoConfig
import dev.zakadi.sdk.encode.BitrateEvent
import dev.zakadi.sdk.encode.EncoderError
import dev.zakadi.sdk.encode.EncoderStarted
import dev.zakadi.sdk.encode.EncoderStopped
import dev.zakadi.sdk.encode.FormatEvent
import dev.zakadi.sdk.encode.KeyframeEvent
import dev.zakadi.sdk.encode.OutputBuffer
import dev.zakadi.sdk.encode.ParameterSetsChanged
import java.util.concurrent.Executor

/**
 * Hands every event of a capture pipeline, from whichever of its threads, to [delegate] on
 * [executor] in the order they came, and runs [after] there after each: the probe's thread sees the
 * events one at a time and the pipeline's threads never wait on it.
 */
class RelayListener(
    private val executor: Executor,
    private val delegate: CaptureListener,
    private val after: () -> Unit = {},
) : CaptureListener {
    private fun relay(block: () -> Unit) = executor.execute {
        block()
        after()
    }

    override fun onFormat(event: FormatEvent) = relay { delegate.onFormat(event) }

    override fun onEncoderStarted(event: EncoderStarted) = relay {
        delegate.onEncoderStarted(event)
    }

    override fun onEncoderStopped(event: EncoderStopped) = relay {
        delegate.onEncoderStopped(event)
    }

    override fun onOutputBuffer(event: OutputBuffer) = relay { delegate.onOutputBuffer(event) }

    override fun onParameterSets(event: ParameterSetsChanged) = relay {
        delegate.onParameterSets(event)
    }

    override fun onKeyframe(event: KeyframeEvent) = relay { delegate.onKeyframe(event) }

    override fun onBitrate(event: BitrateEvent) = relay { delegate.onBitrate(event) }

    override fun onEncoderError(event: EncoderError) = relay { delegate.onEncoderError(event) }

    override fun onFrame(event: FrameEvent) = relay { delegate.onFrame(event) }

    override fun onRung(event: RungEvent) = relay { delegate.onRung(event) }

    override fun onCamera(event: CameraEvent) = relay { delegate.onCamera(event) }

    override fun onClock(event: ClockEvent) = relay { delegate.onClock(event) }

    override fun onExposure(event: ExposureEvent) = relay { delegate.onExposure(event) }

    override fun onVideoConfig(event: VideoConfig) = relay { delegate.onVideoConfig(event) }

    override fun onPathFallback(event: PathFallback) = relay { delegate.onPathFallback(event) }

    override fun onError(event: CaptureError) = relay { delegate.onError(event) }
}
