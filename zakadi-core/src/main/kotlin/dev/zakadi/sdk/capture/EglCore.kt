@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLContext
import android.opengl.EGLDisplay
import android.opengl.EGLExt
import android.opengl.EGLSurface
import android.view.Surface
import dev.zakadi.sdk.InternalZakadiApi

/** An EGL call failed. */
@InternalZakadiApi class EglException(message: String) : RuntimeException(message)

/**
 * An EGL display with an OpenGL ES 2.0 context whose config carries `EGL_RECORDABLE_ANDROID` (spec
 * 07 sections 7.18 and 7.26), so that a window surface on a MediaCodec input surface can be drawn
 * to. One thread owns it (`lv-gl`).
 *
 * @throws EglException when there is no display or no recordable config.
 */
@InternalZakadiApi
class EglCore {
    val display: EGLDisplay = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
    private val config: EGLConfig
    private val context: EGLContext

    /** Whether the config also allows pbuffer surfaces. */
    val pbuffers: Boolean
    private var offscreen: EGLSurface = EGL14.EGL_NO_SURFACE

    init {
        if (display == EGL14.EGL_NO_DISPLAY) throw EglException("eglGetDisplay: no display")
        val version = IntArray(2)
        if (!EGL14.eglInitialize(display, version, 0, version, 1)) {
            throw EglException("eglInitialize: ${error()}")
        }
        val withPbuffers = chooseConfig(EGL14.EGL_WINDOW_BIT or EGL14.EGL_PBUFFER_BIT)
        pbuffers = withPbuffers != null
        config =
            withPbuffers
                ?: chooseConfig(EGL14.EGL_WINDOW_BIT)
                ?: throw EglException("eglChooseConfig: no EGL_RECORDABLE_ANDROID config")
        context =
            EGL14.eglCreateContext(
                display,
                config,
                EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE),
                0,
            )
        if (context == EGL14.EGL_NO_CONTEXT) throw EglException("eglCreateContext: ${error()}")
    }

    /** A window surface on [surface], such as a MediaCodec input surface. */
    fun createWindowSurface(surface: Surface): EGLSurface {
        val s =
            EGL14.eglCreateWindowSurface(display, config, surface, intArrayOf(EGL14.EGL_NONE), 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw EglException("eglCreateWindowSurface: ${error()}")
        }
        return s
    }

    /** An offscreen [width] x [height] surface; needs [pbuffers]. */
    fun createPbufferSurface(width: Int, height: Int): EGLSurface {
        val attributes =
            intArrayOf(EGL14.EGL_WIDTH, width, EGL14.EGL_HEIGHT, height, EGL14.EGL_NONE)
        val s = EGL14.eglCreatePbufferSurface(display, config, attributes, 0)
        if (s == null || s == EGL14.EGL_NO_SURFACE) {
            throw EglException("eglCreatePbufferSurface: ${error()}")
        }
        return s
    }

    /** Makes the context current on [surface]. */
    fun makeCurrent(surface: EGLSurface) {
        if (!EGL14.eglMakeCurrent(display, surface, surface, context)) {
            throw EglException("eglMakeCurrent: ${error()}")
        }
    }

    /** Makes the context current without a window: on a 1x1 pbuffer, or on no surface at all. */
    fun makeOffscreenCurrent() {
        if (offscreen == EGL14.EGL_NO_SURFACE && pbuffers) offscreen = createPbufferSurface(1, 1)
        makeCurrent(offscreen)
    }

    /** `eglPresentationTimeANDROID`: the timestamp the encoder gives the next frame. */
    fun setPresentationTime(surface: EGLSurface, nanos: Long): Boolean =
        EGLExt.eglPresentationTimeANDROID(display, surface, nanos)

    fun swapBuffers(surface: EGLSurface): Boolean = EGL14.eglSwapBuffers(display, surface)

    fun releaseSurface(surface: EGLSurface) {
        if (surface != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, surface)
    }

    /** Releases the context and the display; the object is unusable afterwards. */
    fun release() {
        EGL14.eglMakeCurrent(
            display,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_SURFACE,
            EGL14.EGL_NO_CONTEXT,
        )
        releaseSurface(offscreen)
        offscreen = EGL14.EGL_NO_SURFACE
        EGL14.eglDestroyContext(display, context)
        EGL14.eglReleaseThread()
        EGL14.eglTerminate(display)
    }

    private fun chooseConfig(surfaceType: Int): EGLConfig? {
        val attributes =
            intArrayOf(
                EGL14.EGL_RED_SIZE,
                8,
                EGL14.EGL_GREEN_SIZE,
                8,
                EGL14.EGL_BLUE_SIZE,
                8,
                EGL14.EGL_ALPHA_SIZE,
                8,
                EGL14.EGL_RENDERABLE_TYPE,
                EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE,
                surfaceType,
                EGLExt.EGL_RECORDABLE_ANDROID,
                1,
                EGL14.EGL_NONE,
            )
        val configs = arrayOfNulls<EGLConfig>(1)
        val count = IntArray(1)
        val found = EGL14.eglChooseConfig(display, attributes, 0, configs, 0, 1, count, 0)
        return if (found && count[0] > 0) configs[0] else null
    }

    private fun error(): String = "EGL error 0x" + Integer.toHexString(EGL14.eglGetError())
}
