@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.encode

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.SystemClock
import android.view.Surface
import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import java.util.concurrent.ConcurrentLinkedQueue
import java.util.concurrent.atomic.AtomicInteger

/** No format of the refusal ladder was accepted by the codec. */
@InternalZakadiApi
class EncoderUnavailableException(message: String, cause: Throwable?) :
    IllegalStateException(message, cause)

/**
 * One MediaCodec AVC encoder of spec 07 section 7.19, in asynchronous mode with its callbacks on
 * the thread of the handler it was created with (`lv-venc`). Its output buffers become access units
 * ([AccessUnitAssembler]); it takes keyframe requests, repeated once when no IDR follows within 500
 * ms, and bitrate requests clamped to the codec's range. Everything is reported to its
 * [EncoderListener]; there is no sender, boost or `BitrateGovernor` here.
 */
@InternalZakadiApi
class AvcEncoder
private constructor(
    /** A number unique in the process, which the events carry. */
    val id: Int,
    val codecName: String,
    /** Whether the codec counts as hardware (spec 07 section 7.19): `hw_encode`. */
    val hardware: Boolean,
    val spec: AvcFormatSpec,
    /** The refusal step whose keys the codec accepted. */
    val step: Int,
    /** The codec's bitrate range in bit/s. */
    val bitrateRange: IntRange,
    /** The input surface of path A, or null for buffer input. */
    val inputSurface: Surface?,
    /** `getInputFormat()` after `configure()`: stride and slice height of buffer input. */
    val inputFormat: Map<String, String>,
    private val codec: MediaCodec,
    private val handler: Handler,
    private val listener: EncoderListener,
    private val ptsMsOf: (Long) -> Long,
    private val clock: () -> Long,
) {
    private val assembler = AccessUnitAssembler()
    private val keyframes = KeyframeRequests()
    private val freeInputs = ConcurrentLinkedQueue<Int>()
    @Volatile private var state = RUNNING
    private var eosPending = false
    @Volatile private var lastInputUs = 0L
    private var whenStopped: (() -> Unit)? = null

    /** `config.video.codec` from the first SPS, once the encoder has produced one. */
    @Volatile
    var codecString: String? = null
        private set

    /** Whether the encoder still takes frames and requests. */
    val running: Boolean
        get() = state == RUNNING

    private val repeatCheck = Runnable {
        if (state != RUNNING) return@Runnable
        val now = clock()
        if (synchronized(keyframes) { keyframes.repeatDue(now) }) {
            listener.onKeyframe(KeyframeEvent(now, id, KeyframeEvent.Kind.REPEATED))
            setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
        }
    }

    private val stopTimeout = Runnable { if (state == STOPPING) finish(drained = false) }

    /** Asks for an IDR; with none 500 ms later the request is repeated once. Any thread. */
    fun requestKeyframe() {
        handler.post {
            if (state != RUNNING) return@post
            val now = clock()
            synchronized(keyframes) { keyframes.request(now) }
            listener.onKeyframe(KeyframeEvent(now, id, KeyframeEvent.Kind.REQUESTED))
            setParameter(MediaCodec.PARAMETER_KEY_REQUEST_SYNC_FRAME, 0)
            handler.removeCallbacks(repeatCheck)
            handler.postDelayed(repeatCheck, KEYFRAME_REPEAT_MS)
        }
    }

    /** Sets the bitrate to [bps] clamped to [bitrateRange]. Any thread. */
    fun requestBitrate(bps: Int) {
        handler.post {
            if (state != RUNNING) return@post
            val applied = clampBitrate(bps, bitrateRange)
            setParameter(MediaCodec.PARAMETER_KEY_VIDEO_BITRATE, applied)
            listener.onBitrate(BitrateEvent(clock(), id, bps, applied, bitrateRange))
        }
    }

    /** Counts a frame submitted, for `ms_to_idr` and the frames before an IDR. Any thread. */
    fun onFrameSubmitted() {
        synchronized(keyframes) { keyframes.onFrameSubmitted() }
    }

    /** A free input buffer index of buffer input, or null when none is free. Any thread. */
    fun dequeueInput(): Int? = if (state == RUNNING) freeInputs.poll() else null

    /** Gives back an input buffer index taken with [dequeueInput] and not queued. */
    fun returnInput(index: Int) {
        if (state == RUNNING) freeInputs.add(index)
    }

    /** `getInputImage(index)`: null when the codec offers no flexible YUV image for it. */
    fun inputImage(index: Int): Image? =
        try {
            codec.getInputImage(index)
        } catch (_: IllegalStateException) {
            null
        }

    /** `getInputBuffer(index)`, for colour formats that [inputImage] does not cover. */
    fun inputBuffer(index: Int): ByteBuffer? =
        try {
            codec.getInputBuffer(index)
        } catch (_: IllegalStateException) {
            null
        }

    /** Queues [size] bytes of input buffer [index] at [presentationTimeUs]; false when refused. */
    fun queueInput(index: Int, size: Int, presentationTimeUs: Long): Boolean =
        try {
            codec.queueInputBuffer(index, 0, size, presentationTimeUs, 0)
            lastInputUs = presentationTimeUs
            true
        } catch (_: IllegalStateException) {
            false
        }

    /**
     * Signals the end of the stream, lets the encoder drain for up to [timeoutMs], releases it and
     * then calls [onStopped] on the encoder thread. Any thread; the input surface must no longer be
     * drawn to.
     */
    fun stop(timeoutMs: Long = STOP_TIMEOUT_MS, onStopped: (() -> Unit)? = null) {
        handler.post {
            if (state == STOPPING) {
                val earlier = whenStopped
                whenStopped = {
                    earlier?.invoke()
                    onStopped?.invoke()
                }
                return@post
            }
            if (state == RELEASED) {
                onStopped?.invoke()
                return@post
            }
            state = STOPPING
            whenStopped = onStopped
            try {
                if (inputSurface != null) {
                    codec.signalEndOfInputStream()
                } else {
                    val index = freeInputs.poll()
                    if (index != null) queueEndOfStream(index) else eosPending = true
                }
                handler.postDelayed(stopTimeout, timeoutMs)
            } catch (_: IllegalStateException) {
                finish(drained = false)
            }
        }
    }

    private fun queueEndOfStream(index: Int) {
        codec.queueInputBuffer(index, 0, 0, lastInputUs, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    }

    private fun setParameter(key: String, value: Int) {
        try {
            codec.setParameters(Bundle().apply { putInt(key, value) })
        } catch (e: IllegalStateException) {
            listener.onEncoderError(EncoderError(clock(), id, "$key: $e", recoverable = true))
        }
    }

    private fun onInput(index: Int) {
        when {
            state == RUNNING -> freeInputs.add(index)
            state == STOPPING && eosPending -> {
                eosPending = false
                try {
                    queueEndOfStream(index)
                } catch (_: IllegalStateException) {
                    finish(drained = false)
                }
            }
        }
    }

    private fun onOutput(index: Int, info: MediaCodec.BufferInfo) {
        if (state == RELEASED) return
        val now = clock()
        var assembled: AssembledBuffer? = null
        try {
            val buffer = codec.getOutputBuffer(index)
            if (buffer != null && info.size > 0) {
                val bytes = ByteArray(info.size)
                buffer.duplicate().apply { position(info.offset) }.get(bytes)
                assembled = assembler.assemble(bytes)
            }
            codec.releaseOutputBuffer(index, false)
        } catch (e: IllegalStateException) {
            listener.onEncoderError(EncoderError(now, id, "output: $e", recoverable = false))
        }
        if (assembled?.paramSetsChanged == true) reportParameterSets(now, fromFormat = false)
        val flags = info.flags
        val eos = flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0
        listener.onOutputBuffer(
            OutputBuffer(
                atNanos = now,
                encoderId = id,
                presentationTimeUs = info.presentationTimeUs,
                ptsMs = ptsMsOf(info.presentationTimeUs),
                size = info.size,
                flags = flags,
                keyFlag = flags and MediaCodec.BUFFER_FLAG_KEY_FRAME != 0,
                codecConfig = flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG != 0,
                endOfStream = eos,
                nalTypes = assembled?.nalTypes ?: emptyList(),
                idr = assembled?.idr == true,
                accessUnit = assembled?.accessUnit,
                paramSets = assembled?.paramSets == true,
            )
        )
        if (assembled?.idr == true) {
            val answer = synchronized(keyframes) { keyframes.onIdr(now) }
            if (answer != null) {
                listener.onKeyframe(
                    KeyframeEvent(
                        now,
                        id,
                        KeyframeEvent.Kind.ANSWERED,
                        answer.msToIdr,
                        answer.framesToIdr,
                    )
                )
            }
        }
        if (eos) finish(drained = true)
    }

    private fun onFormatChanged(format: MediaFormat) {
        if (state == RELEASED) return
        val now = clock()
        listener.onFormat(
            FormatEvent(now, id, FormatEvent.Kind.OUTPUT_CHANGED, step, readFormat(format))
        )
        val changed = assembler.learnConfig(csd(format, "csd-0"), csd(format, "csd-1"))
        if (changed) reportParameterSets(now, fromFormat = true)
    }

    private fun onError(e: MediaCodec.CodecException) {
        listener.onEncoderError(
            EncoderError(clock(), id, "${e.diagnosticInfo}: $e", e.isRecoverable || e.isTransient)
        )
    }

    private fun reportParameterSets(now: Long, fromFormat: Boolean) {
        val sets = assembler.parameterSets
        codecString = sets.codecString
        listener.onParameterSets(
            ParameterSetsChanged(now, id, sets.sps, sets.pps, sets.codecString, fromFormat)
        )
    }

    private fun finish(drained: Boolean) {
        if (state == RELEASED) return
        state = RELEASED
        handler.removeCallbacks(stopTimeout)
        handler.removeCallbacks(repeatCheck)
        try {
            codec.stop()
        } catch (_: IllegalStateException) {}
        codec.release()
        inputSurface?.release()
        listener.onEncoderStopped(EncoderStopped(clock(), id, drained))
        whenStopped?.invoke()
        whenStopped = null
    }

    /** Forwards the codec's callbacks to the encoder once it exists. */
    private class Relay : MediaCodec.Callback() {
        @Volatile var target: AvcEncoder? = null

        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            target?.onInput(index)
        }

        override fun onOutputBufferAvailable(
            codec: MediaCodec,
            index: Int,
            info: MediaCodec.BufferInfo,
        ) {
            val encoder = target
            if (encoder != null) encoder.onOutput(index, info)
            else codec.releaseOutputBuffer(index, false)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            target?.onError(e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            target?.onFormatChanged(format)
        }
    }

    companion object {
        private const val RUNNING = 0
        private const val STOPPING = 1
        private const val RELEASED = 2
        private const val KEYFRAME_REPEAT_MS = 505L
        private const val STOP_TIMEOUT_MS = 500L
        private val ids = AtomicInteger()

        /**
         * Configures and starts [info] for [spec], its callbacks on [handler]'s thread. Each
         * refusal of `configure()` or `start()` is retried with the keys of the next step of
         * [FORMAT_REFUSAL_STEPS] on a new codec instance; every format asked, refused and read back
         * goes to [listener]. [ptsMsOf] maps a presentation time in microseconds to `pts_ms`.
         *
         * @throws EncoderUnavailableException when every step is refused.
         */
        fun create(
            info: MediaCodecInfo,
            spec: AvcFormatSpec,
            handler: Handler,
            listener: EncoderListener,
            ptsMsOf: (Long) -> Long = { it / 1000 },
            clock: () -> Long = SystemClock::elapsedRealtimeNanos,
        ): AvcEncoder {
            val id = ids.incrementAndGet()
            val begun = clock()
            val caps = info.getCapabilitiesForType(MediaFormat.MIMETYPE_VIDEO_AVC)
            val support = AvcCodecSupport.of(caps)
            val range = checkNotNull(caps.videoCapabilities).bitrateRange.let { it.lower..it.upper }
            val hardware = !isSoftwareEncoder(info.toCandidate(), Build.VERSION.SDK_INT)
            var refusal: Exception? = null
            for (step in FORMAT_REFUSAL_STEPS.indices) {
                val keys = avcFormatKeys(spec, support, step)
                val asked = asked(spec, keys)
                listener.onFormat(FormatEvent(clock(), id, FormatEvent.Kind.ASKED, step, asked))
                val relay = Relay()
                var codec: MediaCodec? = null
                var surface: Surface? = null
                try {
                    codec = MediaCodec.createByCodecName(info.name)
                    codec.setCallback(relay, handler)
                    codec.configure(
                        avcMediaFormat(spec, keys),
                        null,
                        null,
                        MediaCodec.CONFIGURE_FLAG_ENCODE,
                    )
                    if (spec.surfaceInput) surface = codec.createInputSurface()
                    val input = readFormat(codec.inputFormat)
                    listener.onFormat(
                        FormatEvent(clock(), id, FormatEvent.Kind.INPUT_READ_BACK, step, input)
                    )
                    listener.onFormat(
                        FormatEvent(
                            clock(),
                            id,
                            FormatEvent.Kind.OUTPUT_READ_BACK,
                            step,
                            readFormat(codec.outputFormat),
                        )
                    )
                    val encoder =
                        AvcEncoder(
                            id,
                            info.name,
                            hardware,
                            spec,
                            step,
                            range,
                            surface,
                            input,
                            codec,
                            handler,
                            listener,
                            ptsMsOf,
                            clock,
                        )
                    relay.target = encoder
                    codec.start()
                    listener.onEncoderStarted(
                        EncoderStarted(
                            atNanos = clock(),
                            encoderId = id,
                            codecName = info.name,
                            hardware = hardware,
                            width = spec.width,
                            height = spec.height,
                            fps = spec.fps,
                            bitrateBps = spec.bitrateBps,
                            gopMs = spec.gopMs,
                            surfaceInput = spec.surfaceInput,
                            step = step,
                            bitrateRange = range,
                            setupNanos = clock() - begun,
                        )
                    )
                    return encoder
                } catch (e: Exception) {
                    refusal = e
                    listener.onFormat(
                        FormatEvent(
                            clock(),
                            id,
                            FormatEvent.Kind.REFUSED,
                            step,
                            asked,
                            e.toString(),
                        )
                    )
                    relay.target = null
                    surface?.release()
                    codec?.release()
                }
            }
            throw EncoderUnavailableException("${info.name} refused every format", refusal)
        }

        private fun asked(spec: AvcFormatSpec, keys: Map<String, Number>): Map<String, String> {
            val values = LinkedHashMap<String, String>()
            values[MediaFormat.KEY_MIME] = MediaFormat.MIMETYPE_VIDEO_AVC
            values[MediaFormat.KEY_WIDTH] = spec.width.toString()
            values[MediaFormat.KEY_HEIGHT] = spec.height.toString()
            for ((key, value) in keys) values[key] = value.toString()
            return values
        }

        private fun csd(format: MediaFormat, key: String): ByteArray? {
            if (!format.containsKey(key)) return null
            val buffer = format.getByteBuffer(key) ?: return null
            return ByteArray(buffer.remaining()).also { buffer.duplicate().get(it) }
        }
    }
}
