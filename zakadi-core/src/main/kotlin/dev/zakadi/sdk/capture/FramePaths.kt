@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.annotation.SuppressLint
import android.graphics.SurfaceTexture
import android.media.MediaCodecInfo.CodecCapabilities
import android.opengl.EGL14
import android.opengl.EGLSurface
import android.os.Handler
import android.view.Surface
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.SurfaceRequest
import androidx.camera.core.impl.ConstantObservable
import androidx.camera.core.impl.Observable
import androidx.camera.video.MediaSpec
import androidx.camera.video.VideoOutput
import dev.zakadi.sdk.InternalZakadiApi
import dev.zakadi.sdk.encode.AvcEncoder
import java.util.concurrent.Executor

/** What the frame paths report to the pipeline, on `lv-gl`. */
internal interface FrameSink {
    /** Counts a camera frame and paces it: true when it should go to the encoder. */
    fun onCaptured(timestampNanos: Long): Boolean

    fun onDropped(timestampNanos: Long, reason: DropReason)

    fun onSubmitted(timestampNanos: Long, encoder: AvcEncoder)

    /** The camera's surface or image size and orientation became known or changed. */
    fun onGeometry(width: Int, height: Int, rotationDegrees: Int, hasCameraTransform: Boolean)

    /** Path A: EGL or GL failed; the pipeline moves to path B. */
    fun onEglFailure(e: Exception)

    /** Path B: handling the frame at [timestampNanos] threw [e]. */
    fun onFrameFailure(timestampNanos: Long, e: RuntimeException)

    /** Path B: [encoder] offers no input image; the pipeline re-creates it with a raw format. */
    fun onInputImageUnavailable(encoder: AvcEncoder)
}

/** The front camera as CameraX describes it. */
internal class CameraFacts(
    val sensorRotationDegrees: Int,
    val frontFacing: Boolean,
    val timestampSource: Int?,
)

/**
 * Path A of spec 07 section 7.18: the `VideoOutput` of a `VideoCapture` whose surface is a
 * `SurfaceTexture` on an external texture; each paced frame is drawn in one OpenGL ES pass, rotated
 * upright, unmirrored and cropped to 3:4, into the encoder's input surface, with the camera
 * timestamp as its presentation time. Everything but [onSurfaceRequested] runs on `lv-gl`.
 */
internal class GlFramePath(private val handler: Handler, private val sink: FrameSink) :
    VideoOutput {
    private val executor = Executor { handler.post(it) }
    private val egl = EglCore()
    private val renderer: OesRenderer
    private var input: Input? = null
    private var encoder: AvcEncoder? = null
    private var encoderSurface: EGLSurface = EGL14.EGL_NO_SURFACE
    private val stMatrix = FloatArray(16)
    private var released = false

    /** The camera's orientation, set before the camera is bound. */
    @Volatile var camera: CameraFacts? = null

    init {
        try {
            egl.makeOffscreenCurrent()
            renderer = OesRenderer()
        } catch (e: RuntimeException) {
            egl.release()
            throw e
        }
    }

    private class Input(
        val texture: Int,
        val surfaceTexture: SurfaceTexture,
        val surface: Surface,
        val width: Int,
        val height: Int,
    ) {
        var geometry: FrameGeometry? = null
        var closed = false
    }

    override fun onSurfaceRequested(request: SurfaceRequest) {
        handler.post { provide(request) }
    }

    /**
     * CameraX 1.6.2 refuses to bind a `VideoCapture` whose output has no `MediaSpec` ("MediaSpec
     * can't be null"), and the member that gives one is restricted: the default spec leaves the
     * resolution to CameraX, as spec 07 section 7.18 expects of an output with no capabilities.
     */
    @SuppressLint("RestrictedApi")
    override fun getMediaSpec(): Observable<MediaSpec> =
        ConstantObservable.withValue(MediaSpec.builder().build())

    /** Draws the next frames into [next]'s input surface. */
    fun attach(next: AvcEncoder) {
        detach()
        val surface = checkNotNull(next.inputSurface) { "path A takes a surface-input encoder" }
        encoderSurface = egl.createWindowSurface(surface)
        egl.makeCurrent(encoderSurface)
        encoder = next
    }

    /** Stops drawing into the encoder and destroys the EGL surface on its input surface. */
    fun detach() {
        encoder = null
        if (encoderSurface != EGL14.EGL_NO_SURFACE) {
            egl.makeOffscreenCurrent()
            egl.releaseSurface(encoderSurface)
            encoderSurface = EGL14.EGL_NO_SURFACE
        }
    }

    /** Releases the inputs, the renderer and EGL. */
    fun release() {
        if (released) return
        detach()
        input?.let { close(it) }
        released = true
        renderer.release()
        egl.release()
    }

    private fun provide(request: SurfaceRequest) {
        if (released) {
            request.willNotProvideSurface()
            return
        }
        val size = request.resolution
        val texture = renderer.newTexture()
        val surfaceTexture = SurfaceTexture(texture)
        surfaceTexture.setDefaultBufferSize(size.width, size.height)
        val next = Input(texture, surfaceTexture, Surface(surfaceTexture), size.width, size.height)
        surfaceTexture.setOnFrameAvailableListener({ onFrame(next) }, handler)
        request.setTransformationInfoListener(executor) { info ->
            val facts = camera
            val crop = info.cropRect
            next.geometry =
                FrameGeometry(
                    bufferWidth = next.width,
                    bufferHeight = next.height,
                    crop = PixelRect(crop.left, crop.top, crop.right, crop.bottom),
                    rotationDegrees = info.rotationDegrees,
                    hasCameraTransform = info.hasCameraTransform(),
                    sensorRotationDegrees = facts?.sensorRotationDegrees ?: 0,
                    frontFacing = facts?.frontFacing ?: true,
                )
            if (next === input) {
                sink.onGeometry(
                    next.width,
                    next.height,
                    info.rotationDegrees,
                    info.hasCameraTransform(),
                )
            }
        }
        request.provideSurface(next.surface, executor) { close(next) }
        input = next
    }

    private fun onFrame(frame: Input) {
        if (frame.closed || released) return
        try {
            if (encoderSurface == EGL14.EGL_NO_SURFACE) egl.makeOffscreenCurrent()
            else egl.makeCurrent(encoderSurface)
            frame.surfaceTexture.updateTexImage()
        } catch (e: RuntimeException) {
            sink.onEglFailure(e)
            return
        }
        if (frame !== input) return
        val ts = frame.surfaceTexture.timestamp
        if (!sink.onCaptured(ts)) return
        val target = encoder
        val geometry = frame.geometry
        if (target == null || encoderSurface == EGL14.EGL_NO_SURFACE || !target.running) {
            sink.onDropped(ts, DropReason.NO_ENCODER)
            return
        }
        if (geometry == null) {
            sink.onDropped(ts, DropReason.NO_TRANSFORM)
            return
        }
        frame.surfaceTexture.getTransformMatrix(stMatrix)
        try {
            val matrix = encoderTextureMatrix(geometry, stMatrix)
            renderer.draw(frame.texture, matrix, target.spec.width, target.spec.height)
            egl.setPresentationTime(encoderSurface, ts)
            if (egl.swapBuffers(encoderSurface)) {
                target.onFrameSubmitted()
                sink.onSubmitted(ts, target)
            } else {
                sink.onDropped(ts, DropReason.SUBMIT_FAILED)
            }
        } catch (e: RuntimeException) {
            sink.onDropped(ts, DropReason.SUBMIT_FAILED)
            sink.onEglFailure(e)
        }
    }

    private fun close(frame: Input) {
        if (frame.closed) return
        frame.closed = true
        frame.surfaceTexture.setOnFrameAvailableListener(null)
        frame.surface.release()
        frame.surfaceTexture.release()
        if (!released) renderer.deleteTexture(frame.texture)
        if (input === frame) input = null
    }
}

/**
 * Path B of spec 07 section 7.18: CameraX's `ImageAnalysis` delivers upright YUV frames, and each
 * paced one is cropped to 3:4 and scaled bilinearly into an encoder input buffer: the codec's
 * flexible YUV image, or a semi-planar or planar buffer when it offers none. Runs on `lv-gl`.
 */
internal class ImageFramePath(private val sink: FrameSink) : ImageAnalysis.Analyzer {
    /** The encoder to feed, set and cleared on `lv-gl`. */
    var encoder: AvcEncoder? = null
    private var width = 0
    private var height = 0

    /** The clockwise rotation CameraX applies, set before the camera is bound. */
    @Volatile var rotationDegrees = 0

    override fun analyze(image: ImageProxy) {
        try {
            handle(image)
        } catch (e: RuntimeException) {
            sink.onFrameFailure(image.imageInfo.timestamp, e)
        } finally {
            image.close()
        }
    }

    private fun handle(image: ImageProxy) {
        if (image.width != width || image.height != height) {
            width = image.width
            height = image.height
            sink.onGeometry(width, height, rotationDegrees, hasCameraTransform = false)
        }
        val ts = image.imageInfo.timestamp
        if (!sink.onCaptured(ts)) return
        val target = encoder
        if (target == null || !target.running) {
            sink.onDropped(ts, DropReason.NO_ENCODER)
            return
        }
        val index = target.dequeueInput()
        if (index == null) {
            sink.onDropped(ts, DropReason.ENCODER_BUSY)
            return
        }
        try {
            submit(image, ts, target, index)
        } catch (e: RuntimeException) {
            target.returnInput(index)
            sink.onDropped(ts, DropReason.SUBMIT_FAILED)
            sink.onFrameFailure(ts, e)
        }
    }

    private fun submit(image: ImageProxy, ts: Long, target: AvcEncoder, index: Int) {
        val w = target.spec.width
        val h = target.spec.height
        val source = image.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) }
        val crop = centerCrop3x4(image.width, image.height)
        val size: Int
        val inputImage = target.inputImage(index)
        if (inputImage != null) {
            val planes = inputImage.planes.map { YuvPlane(it.buffer, it.rowStride, it.pixelStride) }
            scaleYuv420(source, crop, planes, w, h)
            size = w * h * 3 / 2
        } else {
            val raw = target.spec.colorFormat
            val layout =
                if (raw == CodecCapabilities.COLOR_FormatYUV420Flexible) null
                else RawYuvLayout.of(raw, w, h, target.inputFormat)
            val buffer = if (layout != null) target.inputBuffer(index) else null
            if (layout == null || buffer == null || buffer.capacity() < layout.size) {
                target.returnInput(index)
                sink.onDropped(ts, DropReason.INPUT_FORMAT)
                sink.onInputImageUnavailable(target)
                return
            }
            buffer.clear()
            scaleYuv420(source, crop, layout.planes(buffer), w, h)
            size = layout.size
        }
        if (target.queueInput(index, size, ts / 1000)) {
            target.onFrameSubmitted()
            sink.onSubmitted(ts, target)
        } else {
            sink.onDropped(ts, DropReason.SUBMIT_FAILED)
        }
    }
}
