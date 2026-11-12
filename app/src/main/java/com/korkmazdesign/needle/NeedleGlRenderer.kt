package com.korkmazdesign.needle

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLSurfaceView
import android.opengl.Matrix
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.nio.FloatBuffer
import javax.microedition.khronos.egl.EGLConfig
import javax.microedition.khronos.opengles.GL10
import kotlin.math.cos
import kotlin.math.sin

/** Plain immutable frame snapshot; Compose publishes it on the UI thread. */
data class NeedleFrame(
    val words: List<String> = emptyList(),
    val activeWord: Int = -1,
    val wordProgress: Float = 0f,
    val audio: Float = 0f
)

object NeedleGlState {
    @Volatile var frame: NeedleFrame = NeedleFrame()
}

class NeedleGlSurfaceView(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(NeedleGlRenderer())
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }
}

private class NeedleGlRenderer : GLSurfaceView.Renderer {
    private val vertexShader = """
        attribute vec2 aPosition;
        attribute float aAlpha;
        uniform float uPointSize;
        varying float vAlpha;
        void main() {
            gl_Position = vec4(aPosition, 0.0, 1.0);
            gl_PointSize = uPointSize;
            vAlpha = aAlpha;
        }
    """
    private val fragmentShader = """
        precision mediump float;
        uniform vec4 uColor;
        uniform float uPointMode;
        varying float vAlpha;
        void main() {
            vec2 p = gl_PointCoord - vec2(0.5);
            float d = length(p);
            float glow = mix(1.0, smoothstep(0.5, 0.05, d), uPointMode);
            gl_FragColor = vec4(uColor.rgb, uColor.a * vAlpha * glow);
        }
    """
    private var program = 0
    private var positionHandle = 0
    private var alphaHandle = 0
    private var colorHandle = 0
    private var pointSizeHandle = 0
    private var pointModeHandle = 0
    private var width = 1
    private var height = 1
    private val startNs = System.nanoTime()
    private val stars = FloatArray(260 * 3)

    init {
        for (i in 0 until 260) {
            val x = ((i * 73) % 997) / 498.5f - 1f
            val y = ((i * 193) % 991) / 495.5f - 1f
            stars[i * 3] = x
            stars[i * 3 + 1] = y
            stars[i * 3 + 2] = .18f + ((i * 17) % 60) / 100f
        }
    }

    override fun onSurfaceCreated(gl: GL10?, config: EGLConfig?) {
        program = GLES20.glCreateProgram().also {
            GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, vertexShader))
            GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, fragmentShader))
            GLES20.glLinkProgram(it)
        }
        positionHandle = GLES20.glGetAttribLocation(program, "aPosition")
        alphaHandle = GLES20.glGetAttribLocation(program, "aAlpha")
        colorHandle = GLES20.glGetUniformLocation(program, "uColor")
        pointSizeHandle = GLES20.glGetUniformLocation(program, "uPointSize")
        pointModeHandle = GLES20.glGetUniformLocation(program, "uPointMode")
        GLES20.glClearColor(.025f, .012f, .025f, 1f)
        GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA, GLES20.GL_ONE_MINUS_SRC_ALPHA)
    }

    override fun onSurfaceChanged(gl: GL10?, w: Int, h: Int) {
        width = w.coerceAtLeast(1)
        height = h.coerceAtLeast(1)
        GLES20.glViewport(0, 0, width, height)
    }

    override fun onDrawFrame(gl: GL10?) {
        val t = (System.nanoTime() - startNs) / 1_000_000_000f
        val state = NeedleGlState.frame
        GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT)
        GLES20.glUseProgram(program)

        // Deep crimson atmospheric field, built from layered translucent star glows.
        val aspect = width.toFloat() / height.toFloat()
        val center = floatArrayOf(0f, 0f, 0f)
        drawPoints(center, floatArrayOf(.20f), 240f * (1f + state.audio * .3f),
            floatArrayOf(.72f, .035f, .16f, .10f))

        val starPositions = FloatArray(260 * 2)
        val starAlpha = FloatArray(260)
        for (i in 0 until 260) {
            starPositions[i * 2] = stars[i * 3]
            starPositions[i * 2 + 1] = stars[i * 3 + 1]
            starAlpha[i] = (stars[i * 3 + 2] * (.58f + .42f * sin(t * 1.8f + i))).coerceIn(.08f, .8f)
        }
        drawPoints(starPositions, starAlpha, 2.2f, floatArrayOf(.94f, .88f, .92f, 1f))

        val wordCount = state.words.size.coerceAtLeast(2)
        val nodes = FloatArray(wordCount * 2)
        for (i in 0 until wordCount) {
            val u = i.toFloat() / (wordCount - 1).toFloat()
            nodes[i * 2] = -.91f + 1.82f * u
            nodes[i * 2 + 1] = .02f + sin(i * 1.21f + t * 1.15f) * .19f +
                cos(i * .57f + t * .72f) * .075f
        }

        // Only the current word's connection is drawn. Its line grows from zero
        // on each word transition instead of leaving the whole old chain on screen.
        val active = state.activeWord
        if (active >= 0 && active < wordCount - 1) {
            val progress = state.wordProgress.coerceIn(0f, 1f)
            val x1 = nodes[active * 2]
            val y1 = nodes[active * 2 + 1]
            val x2 = nodes[(active + 1) * 2]
            val y2 = nodes[(active + 1) * 2 + 1]
            val line = floatArrayOf(x1, y1, x1 + (x2 - x1) * progress, y1 + (y2 - y1) * progress)
            drawLines(line, floatArrayOf(.25f, .95f), 3f,
                floatArrayOf(1f, .78f, .86f, .9f))
            drawPoints(floatArrayOf(x1, y1, x2, y2),
                floatArrayOf(.7f, progress), 12f + state.audio * 8f,
                floatArrayOf(1f, .18f, .34f, .9f))
        }

        val nodeAlpha = FloatArray(wordCount) { i ->
            if (i == active) 1f else if (i == active + 1) .72f else .24f
        }
        drawPoints(nodes, nodeAlpha, 5f, floatArrayOf(1f, .88f, .93f, 1f))
        if (active >= 0 && active < wordCount) {
            val pulse = 1f + .28f * sin(t * 5.2f) + state.audio * .18f
            drawPoints(floatArrayOf(nodes[active * 2], nodes[active * 2 + 1]),
                floatArrayOf(1f), 23f * pulse, floatArrayOf(1f, .12f, .31f, .75f))
            drawPoints(floatArrayOf(nodes[active * 2], nodes[active * 2 + 1]),
                floatArrayOf(1f), 7f * pulse, floatArrayOf(1f, .94f, .97f, 1f))
        }
    }

    private fun drawPoints(data: FloatArray, alpha: FloatArray, size: Float, color: FloatArray) {
        if (data.isEmpty()) return
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(pointSizeHandle, size)
        GLES20.glUniform1f(pointModeHandle, 1f)
        val positions = floatBuffer(data)
        val alphas = floatBuffer(alpha)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(alphaHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positions)
        GLES20.glVertexAttribPointer(alphaHandle, 1, GLES20.GL_FLOAT, false, 0, alphas)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, alpha.size)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(alphaHandle)
    }

    private fun drawLines(data: FloatArray, alpha: FloatArray, size: Float, color: FloatArray) {
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(pointSizeHandle, size)
        GLES20.glUniform1f(pointModeHandle, 0f)
        GLES20.glLineWidth(size.coerceAtMost(4f))
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(alphaHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, floatBuffer(data))
        GLES20.glVertexAttribPointer(alphaHandle, 1, GLES20.GL_FLOAT, false, 0, floatBuffer(alpha))
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, 2)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(alphaHandle)
    }

    private fun floatBuffer(values: FloatArray): FloatBuffer =
        ByteBuffer.allocateDirect(values.size * 4).order(ByteOrder.nativeOrder())
            .asFloatBuffer().apply { put(values); position(0) }

    private fun compile(type: Int, source: String): Int {
        val shader = GLES20.glCreateShader(type)
        GLES20.glShaderSource(shader, source)
        GLES20.glCompileShader(shader)
        val status = IntArray(1)
        GLES20.glGetShaderiv(shader, GLES20.GL_COMPILE_STATUS, status, 0)
        check(status[0] == GLES20.GL_TRUE) { GLES20.glGetShaderInfoLog(shader) }
        return shader
    }
}
