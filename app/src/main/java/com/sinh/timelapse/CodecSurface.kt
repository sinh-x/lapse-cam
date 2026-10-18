package com.sinh.timelapse

import android.graphics.Bitmap
import android.opengl.EGL14
import android.opengl.EGLConfig
import android.opengl.EGLExt
import android.opengl.GLES20
import android.opengl.GLUtils
import android.view.Surface
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer

/** A MediaCodec input surface must be drawn through GLES, not Surface.lockCanvas(). */
internal class CodecSurface(surface: Surface) : AutoCloseable {
    private var display = EGL14.EGL_NO_DISPLAY
    private var context = EGL14.EGL_NO_CONTEXT
    private var window = EGL14.EGL_NO_SURFACE
    private var program = 0
    private var texture = 0
    private val positions = floats(-1f, -1f, 1f, -1f, -1f, 1f, 1f, 1f)
    // Bitmap's first row is the top row; flip the texture's vertical coordinate.
    private val textureCoordinates = floats(0f, 1f, 1f, 1f, 0f, 0f, 1f, 0f)

    init {
        try {
            display = EGL14.eglGetDisplay(EGL14.EGL_DEFAULT_DISPLAY)
            check(display != EGL14.EGL_NO_DISPLAY) { "No EGL display" }
            check(EGL14.eglInitialize(display, IntArray(2), 0, IntArray(2), 0)) { "EGL initialization failed" }
            val configs = arrayOfNulls<EGLConfig>(1)
            val count = IntArray(1)
            check(EGL14.eglChooseConfig(display, intArrayOf(
                EGL14.EGL_RED_SIZE, 8, EGL14.EGL_GREEN_SIZE, 8, EGL14.EGL_BLUE_SIZE, 8,
                EGL14.EGL_RENDERABLE_TYPE, EGL14.EGL_OPENGL_ES2_BIT,
                EGL14.EGL_SURFACE_TYPE, EGL14.EGL_WINDOW_BIT,
                0x3142, 1, // EGL_RECORDABLE_ANDROID
                EGL14.EGL_NONE,
            ), 0, configs, 0, 1, count, 0) && count[0] > 0) { "No recordable EGL configuration" }
            context = EGL14.eglCreateContext(display, configs[0], EGL14.EGL_NO_CONTEXT,
                intArrayOf(EGL14.EGL_CONTEXT_CLIENT_VERSION, 2, EGL14.EGL_NONE), 0)
            check(context != EGL14.EGL_NO_CONTEXT) { "Could not create GLES context" }
            window = EGL14.eglCreateWindowSurface(display, configs[0], surface, intArrayOf(EGL14.EGL_NONE), 0)
            check(window != EGL14.EGL_NO_SURFACE) { "Could not create encoder surface" }
            check(EGL14.eglMakeCurrent(display, window, window, context))
            program = createProgram()
            val textures = IntArray(1)
            GLES20.glGenTextures(1, textures, 0)
            texture = textures[0]
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            checkGl()
        } catch (error: Exception) {
            close()
            throw error
        }
    }

    fun draw(bitmap: Bitmap, presentationTimeNs: Long) {
        GLES20.glViewport(0, 0, VIDEO_WIDTH, VIDEO_HEIGHT)
        GLES20.glUseProgram(program)
        GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES20.GL_TEXTURE_2D, texture)
        GLUtils.texImage2D(GLES20.GL_TEXTURE_2D, 0, bitmap, 0)
        val position = GLES20.glGetAttribLocation(program, "aPosition")
        val coordinate = GLES20.glGetAttribLocation(program, "aTexture")
        GLES20.glEnableVertexAttribArray(position)
        GLES20.glEnableVertexAttribArray(coordinate)
        GLES20.glVertexAttribPointer(position, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(coordinate, 2, GLES20.GL_FLOAT, false, 0, textureCoordinates)
        GLES20.glUniform1i(GLES20.glGetUniformLocation(program, "uTexture"), 0)
        GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4)
        checkGl()
        check(EGLExt.eglPresentationTimeANDROID(display, window, presentationTimeNs))
        check(EGL14.eglSwapBuffers(display, window)) { "Encoder surface swap failed" }
    }

    override fun close() {
        if (display != EGL14.EGL_NO_DISPLAY) {
            if (context != EGL14.EGL_NO_CONTEXT && window != EGL14.EGL_NO_SURFACE) {
                EGL14.eglMakeCurrent(display, window, window, context)
                if (texture != 0) GLES20.glDeleteTextures(1, intArrayOf(texture), 0)
                if (program != 0) GLES20.glDeleteProgram(program)
            }
            EGL14.eglMakeCurrent(display, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_SURFACE, EGL14.EGL_NO_CONTEXT)
            if (window != EGL14.EGL_NO_SURFACE) EGL14.eglDestroySurface(display, window)
            if (context != EGL14.EGL_NO_CONTEXT) EGL14.eglDestroyContext(display, context)
            EGL14.eglReleaseThread()
            EGL14.eglTerminate(display)
        }
        display = EGL14.EGL_NO_DISPLAY
        context = EGL14.EGL_NO_CONTEXT
        window = EGL14.EGL_NO_SURFACE
        texture = 0
        program = 0
    }

    private fun createProgram(): Int {
        val vertex = shader(GLES20.GL_VERTEX_SHADER, """
            attribute vec2 aPosition;
            attribute vec2 aTexture;
            varying vec2 vTexture;
            void main() { gl_Position = vec4(aPosition, 0.0, 1.0); vTexture = aTexture; }
        """.trimIndent())
        var fragment = 0
        var result = 0
        try {
            fragment = shader(GLES20.GL_FRAGMENT_SHADER, """
                precision mediump float;
                uniform sampler2D uTexture;
                varying vec2 vTexture;
                void main() { gl_FragColor = texture2D(uTexture, vTexture); }
            """.trimIndent())
            result = GLES20.glCreateProgram()
            GLES20.glAttachShader(result, vertex)
            GLES20.glAttachShader(result, fragment)
            GLES20.glLinkProgram(result)
            val linked = IntArray(1)
            GLES20.glGetProgramiv(result, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] != 0) { GLES20.glGetProgramInfoLog(result) }
            return result
        } catch (error: Exception) {
            if (result != 0) GLES20.glDeleteProgram(result)
            throw error
        } finally {
            GLES20.glDeleteShader(vertex)
            if (fragment != 0) GLES20.glDeleteShader(fragment)
        }
    }

    private fun shader(type: Int, source: String): Int {
        val result = GLES20.glCreateShader(type)
        GLES20.glShaderSource(result, source)
        GLES20.glCompileShader(result)
        val compiled = IntArray(1)
        GLES20.glGetShaderiv(result, GLES20.GL_COMPILE_STATUS, compiled, 0)
        if (compiled[0] == 0) {
            val message = GLES20.glGetShaderInfoLog(result)
            GLES20.glDeleteShader(result)
            error(message)
        }
        return result
    }

    private fun checkGl() {
        check(GLES20.glGetError() == GLES20.GL_NO_ERROR) { "GLES rendering failed" }
    }

    private fun floats(vararg values: Float): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
            .apply { put(values); position(0) }
}
