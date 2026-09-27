@file:OptIn(InternalZakadiApi::class)

package dev.zakadi.sdk.capture

import android.opengl.GLES11Ext
import android.opengl.GLES20
import dev.zakadi.sdk.InternalZakadiApi
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/**
 * The OpenGL ES 2.0 pass of path A (spec 07 section 7.18): it draws an external (camera) texture
 * over the whole viewport through a texture matrix, which rotates, crops to 3:4 and scales in one
 * pass ([encoderTextureMatrix]). Its context must be current on the calling thread.
 */
@InternalZakadiApi
class OesRenderer {
    private val program: Int
    private val position: Int
    private val textureCoord: Int
    private val textureMatrix: Int

    init {
        val vertex = compile(GLES20.GL_VERTEX_SHADER, VERTEX_SHADER)
        val fragment = compile(GLES20.GL_FRAGMENT_SHADER, FRAGMENT_SHADER)
        program = GLES20.glCreateProgram()
        GLES20.glAttachShader(program, vertex)
        GLES20.glAttachShader(program, fragment)
        GLES20.glLinkProgram(program)
        val linked = IntArray(1)
        GLES20.glGetProgramiv(program, GLES20.GL_LINK_STATUS, linked, 0)
        GLES20.glDeleteShader(vertex)
        GLES20.glDeleteShader(fragment)
        if (linked[0] == 0) {
            val log = GLES20.glGetProgramInfoLog(program)
            GLES20.glDeleteProgram(program)
            throw EglException("glLinkProgram: $log")
        }
        position = GLES20.glGetAttribLocation(program, "aPosition")
        textureCoord = GLES20.glGetAttribLocation(program, "aTextureCoord")
        textureMatrix = GLES20.glGetUniformLocation(program, "uTexMatrix")
    }

    /** A new external texture for a `SurfaceTexture`, sampled linearly and clamped to its edges. */
    fun newTexture(): Int {
        val names = IntArray(1)
        GLES20.glGenTextures(1, names, 0)
        val target = GLES11Ext.GL_TEXTURE_EXTERNAL_OES
        GLES20.glBindTexture(target, names[0])
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glTexParameteri(target, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
        GLES20.glBindTexture(target, 0)
        return names[0]
    }

    fun deleteTexture(texture: Int) {
        GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
    }

    /** Draws [texture] through [matrix] into the current [width] x [height] surface. */
    fun draw(texture: Int, matrix: FloatArray, width: Int, height: Int) {
        GLES20.glViewport(0, 0, width, height)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, texture)
        GLES20.glUniformMatrix4fv(textureMatrix, 1, false, matrix, 0)
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 8, VERTICES)
        GLES20.glEnableVertexAttribArray(textureCoord)
        GLES20.glVertexAttribPointer(textureCoord, 2, GLES20.GL_FLOAT, false, 8, TEXTURE_COORDS)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        GLES20.glDisableVertexAttribArray(position)
        GLES20.glDisableVertexAttribArray(textureCoord)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, 0)
        GLES20.glUseProgram(0)
    }

    fun release() {
        GLES20.glDeleteProgram(program)
    }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val log = GLES20.glGetShaderInfoLog(shader)
            GLES20.glDeleteShader(shader)
            throw EglException("glCompileShader: $log")
        }
        return shader
    }

    private companion object {
        const val VERTEX_SHADER =
            """
            uniform mat4 uTexMatrix;
            attribute vec4 aPosition;
            attribute vec4 aTextureCoord;
            varying vec2 vTextureCoord;
            void main() {
                gl_Position = aPosition;
                vTextureCoord = (uTexMatrix * aTextureCoord).xy;
            }
            """

        const val FRAGMENT_SHADER =
            """
            #extension GL_OES_EGL_image_external : require
            #ifdef GL_FRAGMENT_PRECISION_HIGH
            precision highp float;
            #else
            precision mediump float;
            #endif
            varying vec2 vTextureCoord;
            uniform samplerExternalOES sTexture;
            void main() {
                gl_FragColor = texture2D(sTexture, vTextureCoord);
            }
            """

        /** The whole viewport, bottom left first, as a triangle strip. */
        val VERTICES: FloatBuffer = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)

        /** Texture coordinates 0 to 1 from the bottom left, matching [VERTICES]. */
        val TEXTURE_COORDS: FloatBuffer = floats(0f, 0f, 1f, 0f, 0f, 1f, 1f, 1f)

        fun floats(vararg values: Float): FloatBuffer =
            ByteBuffer.allocateDirect(values.size * 4)
                .order(ByteOrder.nativeOrder())
                .asFloatBuffer()
                .apply {
                    put(values)
                    position(0)
                }
    }
}
