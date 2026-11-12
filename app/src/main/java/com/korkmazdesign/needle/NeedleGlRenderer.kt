package com.korkmazdesign.needle

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.opengl.GLES11Ext
import android.view.Surface
import android.content.res.AssetFileDescriptor
import android.util.Log
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

object NeedleFrameFactory {
    fun create(lines: List<LyricLine>, positionMs: Long, audio: Float): NeedleFrame {
        val activeLine = lines.indexOfLast { it.timeMs <= positionMs }
        val line = lines.getOrNull(activeLine)
        val words = line?.text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
        val nextTime = lines.getOrNull(activeLine + 1)?.timeMs
        val duration = if (line != null && nextTime != null)
            (nextTime - line.timeMs).coerceAtLeast(1000L) else 4000L
        val progress = if (line != null)
            ((positionMs - line.timeMs).toFloat() / duration).coerceIn(0f, .999f) else 0f
        val activeWord = if (words.isNotEmpty())
            (progress * words.size).toInt().coerceIn(0, words.lastIndex) else -1
        val wordProgress = if (words.isNotEmpty())
            (progress * words.size - activeWord).coerceIn(0f, 1f) else 0f
        return NeedleFrame(words, activeWord, wordProgress, audio.coerceIn(0f, 1f))
    }
}

class NeedleGlSurfaceView(context: Context) : GLSurfaceView(context) {
    init {
        setEGLContextClientVersion(2)
        setRenderer(NeedleGlRenderer(context))
        renderMode = RENDERMODE_CONTINUOUSLY
        preserveEGLContextOnPause = true
    }
}

private class NeedleGlRenderer(private val context: Context) : GLSurfaceView.Renderer {
    private val videoVertexShader = """
        attribute vec4 aPosition; attribute vec2 aTexCoord;
        uniform mat4 uTexMatrix; varying vec2 vTexCoord;
        void main() { gl_Position = aPosition; vTexCoord = (uTexMatrix * vec4(aTexCoord, 0.0, 1.0)).xy; }
    """
    private val videoFragmentShader = """
        #extension GL_OES_EGL_image_external : require
        precision mediump float; uniform samplerExternalOES uVideo; varying vec2 vTexCoord;
        void main() { gl_FragColor = texture2D(uVideo, vTexCoord); }
    """
    private var videoProgram = 0
    private var videoTexture = 0
    private var videoSurfaceTexture: SurfaceTexture? = null
    private var videoSurface: Surface? = null
    private var mediaPlayer: MediaPlayer? = null
    @Volatile private var videoFrameAvailable = false
    private val videoMatrix = FloatArray(16)
    private var videoPositionHandle = 0
    private var videoTexCoordHandle = 0
    private var videoMatrixHandle = 0
    private var videoSamplerHandle = 0
    private val videoQuad = ByteBuffer.allocateDirect(64).order(ByteOrder.nativeOrder()).asFloatBuffer().apply {
        put(floatArrayOf(-1f,-1f,0f,1f, 1f,-1f,1f,1f, -1f,1f,0f,0f, 1f,1f,1f,0f)); position(0)
    }

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
            float glow = 1.0;
            if (uPointMode > 0.5) {
                vec2 p = gl_PointCoord - vec2(0.5);
                float d = length(p);
                glow = 1.0 - smoothstep(0.035, 0.5, d);
            }
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
    private val starPositions = FloatArray(260 * 2)
    private val starAlpha = FloatArray(260)
    private val rippleVertices = FloatArray(48 * 4)
    private val rippleAlpha = FloatArray(48 * 2)
    private val positionBuffer: FloatBuffer = ByteBuffer.allocateDirect(2048 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()
    private val alphaBuffer: FloatBuffer = ByteBuffer.allocateDirect(1024 * 4).order(ByteOrder.nativeOrder()).asFloatBuffer()

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
        GLES20.glClearColor(.008f, .006f, .012f, 1f)
        // The supplied clip is a filmed reference, not a background video. Render the scene natively.
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
        // Native OpenGL scene; no screen-recording video is composited.
        GLES20.glUseProgram(program)

        // Animated crimson light field. Multiple drifting radial glows create depth.
        val driftA = floatArrayOf(.46f * sin(t * .19f), .18f * cos(t * .16f))
        val driftB = floatArrayOf(.52f * cos(t * .13f + 1.4f), -.24f * sin(t * .17f))
        drawPoints(driftA, floatArrayOf(.24f), 330f + state.audio * 36f,
            floatArrayOf(.78f, .012f, .105f, .22f))
        drawPoints(driftB, floatArrayOf(.18f), 265f + state.audio * 24f,
            floatArrayOf(.42f, .008f, .075f, .17f))
        drawPoints(floatArrayOf(-.55f * cos(t * .11f), .40f * sin(t * .14f)),
            floatArrayOf(.12f), 210f, floatArrayOf(.95f, .025f, .14f, .12f))

        for (i in 0 until 260) {
            starPositions[i * 2] = stars[i * 3]
            starPositions[i * 2 + 1] = stars[i * 3 + 1]
            starAlpha[i] = (stars[i * 3 + 2] * (.58f + .42f * sin(t * 1.8f + i))).coerceIn(.08f, .8f)
        }
        drawPoints(starPositions, starAlpha, 2.2f, floatArrayOf(.78f, .66f, .72f, .92f))

        val wordCount = state.words.size.coerceAtLeast(2)
        val nodes = FloatArray(wordCount * 2)
        for (i in 0 until wordCount) {
            val u = i.toFloat() / (wordCount - 1).toFloat()
            nodes[i * 2] = -.91f + 1.82f * u
            nodes[i * 2 + 1] = .02f + sin(i * 1.21f + t * 1.15f) * .19f +
                cos(i * .57f + t * .72f) * .075f
        }

        // Build the full constellation from left to right; completed links remain visible.
        val active = state.activeWord
        val reveal = if (active < 0) 0f else ((active + state.wordProgress) / (wordCount - 1).toFloat()).coerceIn(0f, 1f)
        val fadeOut = if (reveal > .94f) ((1f - reveal) / .06f).coerceIn(0f, 1f) else 1f
        if (active >= 0) {
            val lineVertices = FloatArray((wordCount - 1) * 4)
            val lineAlpha = FloatArray((wordCount - 1) * 2)
            for (edge in 0 until wordCount - 1) {
                val base = edge * 4
                lineVertices[base] = nodes[edge * 2]
                lineVertices[base + 1] = nodes[edge * 2 + 1]
                val edgeProgress = when { edge < active -> 1f; edge == active -> state.wordProgress.coerceIn(0f, 1f); else -> 0f }
                lineVertices[base + 2] = nodes[edge * 2] + (nodes[(edge + 1) * 2] - nodes[edge * 2]) * edgeProgress
                lineVertices[base + 3] = nodes[edge * 2 + 1] + (nodes[(edge + 1) * 2 + 1] - nodes[edge * 2 + 1]) * edgeProgress
                val a = if (edge <= active) (.34f + .58f * edgeProgress) * fadeOut else 0f
                lineAlpha[edge * 2] = a
                lineAlpha[edge * 2 + 1] = a
            }
            drawLines(lineVertices, lineAlpha, 1.5f, floatArrayOf(.94f, .94f, 1f, .96f), (wordCount - 1) * 2)
            // Fine diagonal cross-links turn the chain into a constellation figure.
            val crossVertices = FloatArray((wordCount - 2) * 4)
            val crossAlpha = FloatArray((wordCount - 2) * 2)
            for (edge in 0 until wordCount - 2) {
                val base = edge * 4
                crossVertices[base] = nodes[edge * 2]
                crossVertices[base + 1] = nodes[edge * 2 + 1]
                crossVertices[base + 2] = nodes[(edge + 2) * 2]
                crossVertices[base + 3] = nodes[(edge + 2) * 2 + 1]
                val shown = edge + 2 <= active
                val a = if (shown && edge % 2 == 0) .48f * fadeOut else 0f
                crossAlpha[edge * 2] = a
                crossAlpha[edge * 2 + 1] = a
            }
            drawLines(crossVertices, crossAlpha, 1f, floatArrayOf(.82f, .88f, 1f, .72f), (wordCount - 2) * 2)
        }

        val nodeAlpha = FloatArray(wordCount) { i ->
            if (i == active) 1f else if (i == active + 1) .72f else .24f
        }
        drawPoints(nodes, nodeAlpha, 5f, floatArrayOf(.90f, .76f, .81f, .96f))
        if (active >= 0 && active < wordCount) {
            val pulse = 1f + .28f * sin(t * 5.2f) + state.audio * .18f
            val cx = nodes[active * 2]
            val cy = nodes[active * 2 + 1]

            // Three soft concentric ripples expand from the active lyric star.
            // Their phase follows the current word, so every new word restarts the wave.
            for (ring in 0 until 3) {
                val phase = (state.wordProgress + ring * .34f) % 1f
                val radius = .025f + phase * .205f
                val alpha = (1f - phase) * .72f
                for (segment in 0 until 48) {
                    val a0 = (segment.toFloat() / 48f) * (Math.PI * 2.0)
                    val a1 = ((segment + 1).toFloat() / 48f) * (Math.PI * 2.0)
                    val index = segment * 4
                    rippleVertices[index] = cx + cos(a0).toFloat() * radius
                    rippleVertices[index + 1] = cy + sin(a0).toFloat() * radius
                    rippleVertices[index + 2] = cx + cos(a1).toFloat() * radius
                    rippleVertices[index + 3] = cy + sin(a1).toFloat() * radius
                    rippleAlpha[segment * 2] = alpha
                    rippleAlpha[segment * 2 + 1] = alpha
                }
                drawLines(rippleVertices, rippleAlpha, 1.5f,
                    floatArrayOf(1f, .28f, .43f, .82f), 96)
            }

            drawPoints(floatArrayOf(cx, cy),
                floatArrayOf(1f), 23f * pulse, floatArrayOf(.88f, .018f, .12f, .82f))
            drawPoints(floatArrayOf(cx, cy),
                floatArrayOf(1f), 7f * pulse, floatArrayOf(1f, .82f, .87f, 1f))
        }
    }


    private fun setupVideoBackground() {
        try {
            val ids = IntArray(1); GLES20.glGenTextures(1, ids, 0); videoTexture = ids[0]
            GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MIN_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_MAG_FILTER, GLES20.GL_LINEAR)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_S, GLES20.GL_CLAMP_TO_EDGE)
            GLES20.glTexParameteri(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, GLES20.GL_TEXTURE_WRAP_T, GLES20.GL_CLAMP_TO_EDGE)
            videoProgram = GLES20.glCreateProgram().also {
                GLES20.glAttachShader(it, compile(GLES20.GL_VERTEX_SHADER, videoVertexShader))
                GLES20.glAttachShader(it, compile(GLES20.GL_FRAGMENT_SHADER, videoFragmentShader)); GLES20.glLinkProgram(it)
            }
            val linked = IntArray(1); GLES20.glGetProgramiv(videoProgram, GLES20.GL_LINK_STATUS, linked, 0)
            check(linked[0] == GLES20.GL_TRUE) { GLES20.glGetProgramInfoLog(videoProgram) }
            videoPositionHandle = GLES20.glGetAttribLocation(videoProgram, "aPosition")
            videoTexCoordHandle = GLES20.glGetAttribLocation(videoProgram, "aTexCoord")
            videoMatrixHandle = GLES20.glGetUniformLocation(videoProgram, "uTexMatrix")
            videoSamplerHandle = GLES20.glGetUniformLocation(videoProgram, "uVideo")
            android.opengl.Matrix.setIdentityM(videoMatrix, 0)
            videoSurfaceTexture = SurfaceTexture(videoTexture).also { it.setOnFrameAvailableListener { videoFrameAvailable = true } }
            videoSurface = Surface(videoSurfaceTexture)
            val afd: AssetFileDescriptor = context.assets.openFd("needle_background_clean_final.mp4")
            val player = MediaPlayer(); mediaPlayer = player
            player.setDataSource(afd.fileDescriptor, afd.startOffset, afd.length); afd.close()
            player.setSurface(videoSurface); player.isLooping = true
            player.setOnPreparedListener { mp ->
                try { videoSurfaceTexture?.setDefaultBufferSize(mp.videoWidth.coerceAtLeast(1), mp.videoHeight.coerceAtLeast(1)); mp.start() }
                catch (e: Exception) { Log.e("NEEDLE_VIDEO", "Video start failed", e) }
            }
            player.setOnErrorListener { _, what, extra -> Log.e("NEEDLE_VIDEO", "Playback error $what/$extra"); true }
            player.prepareAsync()
        } catch (e: Exception) { Log.e("NEEDLE_VIDEO", "Background video setup failed", e) }
    }

    private fun drawVideoBackground() {
        val st = videoSurfaceTexture ?: return
        if (videoFrameAvailable) {
            try { st.updateTexImage(); st.getTransformMatrix(videoMatrix); videoFrameAvailable = false }
            catch (e: Exception) { Log.w("NEEDLE_VIDEO", "Texture update failed", e); return }
        } else if (mediaPlayer == null) return
        GLES20.glUseProgram(videoProgram); GLES20.glActiveTexture(GLES20.GL_TEXTURE0)
        GLES20.glBindTexture(GLES11Ext.GL_TEXTURE_EXTERNAL_OES, videoTexture)
        GLES20.glUniform1i(videoSamplerHandle, 0); GLES20.glUniformMatrix4fv(videoMatrixHandle, 1, false, videoMatrix, 0)
        videoQuad.position(0); GLES20.glEnableVertexAttribArray(videoPositionHandle); GLES20.glEnableVertexAttribArray(videoTexCoordHandle)
        GLES20.glVertexAttribPointer(videoPositionHandle, 2, GLES20.GL_FLOAT, false, 16, videoQuad)
        videoQuad.position(2); GLES20.glVertexAttribPointer(videoTexCoordHandle, 2, GLES20.GL_FLOAT, false, 16, videoQuad)
        GLES20.glDisable(GLES20.GL_BLEND); GLES20.glDrawArrays(GLES20.GL_TRIANGLE_STRIP, 0, 4); GLES20.glEnable(GLES20.GL_BLEND)
        GLES20.glDisableVertexAttribArray(videoPositionHandle); GLES20.glDisableVertexAttribArray(videoTexCoordHandle)
    }

    private fun drawPoints(data: FloatArray, alpha: FloatArray, size: Float, color: FloatArray) {
        if (data.isEmpty()) return
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(pointSizeHandle, size)
        GLES20.glUniform1f(pointModeHandle, 1f)
        positionBuffer.clear()
        positionBuffer.put(data)
        positionBuffer.position(0)
        alphaBuffer.clear()
        alphaBuffer.put(alpha)
        alphaBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(alphaHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glVertexAttribPointer(alphaHandle, 1, GLES20.GL_FLOAT, false, 0, alphaBuffer)
        GLES20.glDrawArrays(GLES20.GL_POINTS, 0, alpha.size)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(alphaHandle)
    }

    private fun drawLines(data: FloatArray, alpha: FloatArray, size: Float, color: FloatArray, vertexCount: Int = 2) {
        GLES20.glUniform4fv(colorHandle, 1, color, 0)
        GLES20.glUniform1f(pointSizeHandle, size)
        GLES20.glUniform1f(pointModeHandle, 0f)
        GLES20.glLineWidth(size.coerceAtMost(4f))
        positionBuffer.clear()
        positionBuffer.put(data)
        positionBuffer.position(0)
        alphaBuffer.clear()
        alphaBuffer.put(alpha)
        alphaBuffer.position(0)
        GLES20.glEnableVertexAttribArray(positionHandle)
        GLES20.glEnableVertexAttribArray(alphaHandle)
        GLES20.glVertexAttribPointer(positionHandle, 2, GLES20.GL_FLOAT, false, 0, positionBuffer)
        GLES20.glVertexAttribPointer(alphaHandle, 1, GLES20.GL_FLOAT, false, 0, alphaBuffer)
        GLES20.glDrawArrays(GLES20.GL_LINES, 0, vertexCount)
        GLES20.glDisableVertexAttribArray(positionHandle)
        GLES20.glDisableVertexAttribArray(alphaHandle)
    }

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
