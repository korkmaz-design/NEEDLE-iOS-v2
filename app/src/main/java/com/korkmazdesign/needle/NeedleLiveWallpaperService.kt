package com.korkmazdesign.needle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.text.format.DateFormat
import android.view.SurfaceHolder
import java.util.Date
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * Needle live wallpaper renderer.
 * Uses a procedural, continuously moving crimson field and a fine white constellation.
 * Geometry and scratch arrays are retained between frames to avoid per-frame object churn.
 */
class NeedleLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NeedleEngine()

    private inner class NeedleEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG or Paint.FILTER_BITMAP_FLAG)
        private val backgroundPath = Path()
        private val visibleStars = FloatArray(180 * 2)
        private val starSizes = FloatArray(180)
        private val nodeX = FloatArray(32)
        private val nodeY = FloatArray(32)
        private var visible = false
        private var frame = 0f
        private var lastClockMinute = -1L
        private var clockText = ""
        private val tick = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 16L)
            }
        }

        init {
            for (i in 0 until 180) {
                visibleStars[i * 2] = ((i * 157) % 997) / 997f
                visibleStars[i * 2 + 1] = ((i * 317) % 991) / 991f
                starSizes[i] = if (i % 13 == 0) 1.45f else if (i % 5 == 0) .9f else .48f
            }
        }

        override fun onVisibilityChanged(visibleNow: Boolean) {
            visible = visibleNow
            handler.removeCallbacks(tick)
            if (visible) handler.post(tick)
        }

        override fun onSurfaceChanged(holder: SurfaceHolder, format: Int, width: Int, height: Int) {
            super.onSurfaceChanged(holder, format, width, height)
            drawFrame()
        }

        override fun onSurfaceDestroyed(holder: SurfaceHolder) {
            visible = false
            handler.removeCallbacks(tick)
            super.onSurfaceDestroyed(holder)
        }

        override fun onDestroy() {
            visible = false
            handler.removeCallbacks(tick)
            super.onDestroy()
        }

        private fun drawFrame() {
            var canvas: Canvas? = null
            try {
                canvas = surfaceHolder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) surfaceHolder.unlockCanvasAndPost(canvas)
            }
        }

        private fun render(c: Canvas) {
            val w = c.width.toFloat()
            val h = c.height.toFloat()
            if (w < 2f || h < 2f) return
            val t = frame
            val audio = NeedleAudioReactive.level.coerceIn(0f, 1f)

            // Layered, drifting crimson light fields: no static flat-color background.
            c.drawColor(Color.rgb(3, 2, 5))
            paint.style = Paint.Style.FILL
            paint.shader = RadialGradient(
                w * (.48f + .06f * sin(t * .23f)), h * (.46f + .04f * cos(t * .19f)),
                max(w, h) * .82f,
                intArrayOf(Color.rgb(118, 5, 31), Color.rgb(63, 3, 23), Color.rgb(12, 4, 12), Color.rgb(3, 2, 5)),
                floatArrayOf(0f, .34f, .72f, 1f), Shader.TileMode.CLAMP
            )
            c.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            drawLightRibbon(c, w, h, t, 0f, Color.argb(95, 255, 15, 57), h * .17f)
            drawLightRibbon(c, w, h, t, 1.7f, Color.argb(58, 255, 65, 91), h * .105f)
            drawLightRibbon(c, w, h, t, 3.1f, Color.argb(42, 150, 0, 43), h * .24f)

            // Sparse fine white dust and occasional four-point star glints.
            for (i in 0 until 180) {
                val x = visibleStars[i * 2] * w
                val y = visibleStars[i * 2 + 1] * h
                val twinkle = .45f + .55f * (.5f + .5f * sin(t * (1.2f + i % 5) + i * 1.7f))
                val alpha = (35f + twinkle * if (i % 13 == 0) 190f else 75f).toInt().coerceIn(15, 230)
                paint.color = Color.argb(alpha, 255, 250, 252)
                c.drawCircle(x, y, starSizes[i] * (if (i % 13 == 0) 1f + audio * .35f else 1f), paint)
                if (i % 13 == 0 && alpha > 145) drawGlint(c, x, y, 3.5f + twinkle * 2f, alpha)
            }

            val lyrics = NeedleMusicState.lyrics.value
            val basePosition = NeedleMusicState.positionMs.value
            val updatedAt = NeedleMusicState.positionUpdatedAt.value
            val position = if (NeedleMusicState.playing.value && updatedAt > 0L)
                basePosition + (SystemClock.elapsedRealtime() - updatedAt).coerceAtLeast(0L) else basePosition
            val lyric = NeedleFrameFactory.create(lyrics, position, audio)
            val words = lyric.words
            val active = lyric.activeWord
            val wordCount = words.size
            val count = max(8, min(16, wordCount.coerceAtLeast(8)))
            val centerY = h * .51f

            // A deliberately irregular constellation, not a single straight chain.
            for (i in 0 until count) {
                val u = i.toFloat() / (count - 1).toFloat()
                nodeX[i] = w * (.16f + .68f * u)
                nodeY[i] = centerY + h * (
                    .075f * sin(i * 1.37f + t * .23f) +
                    .035f * cos(i * .71f - t * .17f) +
                    .025f * sin(i * 2.43f)
                )
            }

            if (active >= 0 && wordCount > 0) {
                val reveal = ((active + lyric.wordProgress) / wordCount.toFloat()).coerceIn(0f, 1f)
                val fade = if (reveal > .96f) ((1f - reveal) / .04f).coerceIn(0f, 1f) else 1f
                val visibleNode = min(count - 1, ((active + lyric.wordProgress) * count / wordCount).toInt())
                paint.style = Paint.Style.STROKE
                paint.strokeCap = Paint.Cap.ROUND
                paint.strokeJoin = Paint.Join.ROUND
                // Main backbone plus alternating diagonals and cross-links.
                for (edge in 0 until count - 1) {
                    val progress = when {
                        edge < visibleNode -> 1f
                        edge == visibleNode -> lyric.wordProgress
                        else -> 0f
                    }
                    if (progress <= 0f) continue
                    paint.color = Color.argb((220f * fade).toInt().coerceIn(0, 220), 255, 255, 255)
                    paint.strokeWidth = 1.0f + audio * .65f
                    val ex = nodeX[edge] + (nodeX[edge + 1] - nodeX[edge]) * progress
                    val ey = nodeY[edge] + (nodeY[edge + 1] - nodeY[edge]) * progress
                    c.drawLine(nodeX[edge], nodeY[edge], ex, ey, paint)
                }
                for (edge in 0 until count - 2) {
                    val target = edge + 2
                    if (target > visibleNode || edge % 2 != 0) continue
                    paint.color = Color.argb((105f * fade).toInt().coerceIn(0, 105), 245, 247, 255)
                    paint.strokeWidth = .72f
                    c.drawLine(nodeX[edge], nodeY[edge], nodeX[target], nodeY[target], paint)
                }

                val activeNode = min(count - 1, (active.toFloat() / wordCount * count).toInt())
                val ax = nodeX[activeNode]
                val ay = nodeY[activeNode]
                // Three thin white pulse rings with a soft crimson halo.
                paint.style = Paint.Style.STROKE
                for (ring in 0 until 3) {
                    val phase = (lyric.wordProgress + ring * .34f) % 1f
                    paint.color = Color.argb(((1f - phase) * 170f).toInt(), 255, 255, 255)
                    paint.strokeWidth = if (ring == 0) 1.15f else .8f
                    c.drawCircle(ax, ay, 5f + phase * h * .065f, paint)
                }
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(75, 255, 20, 55)
                c.drawCircle(ax, ay, 13f + audio * 5f, paint)
            }

            // Sharp white star nodes, each with a tiny center and restrained halo.
            paint.style = Paint.Style.FILL
            for (i in 0 until count) {
                val isLit = active >= 0 && i <= min(count - 1, (active.toFloat() / max(1, wordCount) * count).toInt())
                paint.color = Color.argb(if (isLit) 50 else 22, 255, 255, 255)
                c.drawCircle(nodeX[i], nodeY[i], if (isLit) 6f else 3.4f, paint)
                paint.color = Color.argb(if (isLit) 255 else 190, 255, 255, 255)
                c.drawCircle(nodeX[i], nodeY[i], if (i % 3 == 0) 1.75f else 1.2f, paint)
                if (i % 3 == 0) drawGlint(c, nodeX[i], nodeY[i], 4.3f, if (isLit) 220 else 115)
            }

            // Lyrics stay readable and restrained, like the reference screen.
            if (wordCount > 0) {
                paint.textAlign = Paint.Align.CENTER
                paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
                for (i in 0 until wordCount) {
                    val node = min(count - 1, (i.toFloat() / max(1, wordCount) * count).toInt())
                    val isActive = i == active
                    paint.color = if (isActive) Color.WHITE else Color.argb(165, 255, 250, 252)
                    paint.textSize = if (isActive) (w * .032f).coerceIn(17f, 27f) else (w * .017f).coerceIn(9f, 14f)
                    paint.typeface = Typeface.create("sans-serif", if (isActive) Typeface.BOLD else Typeface.NORMAL)
                    val label = fitWord(words[i], paint, w * .13f)
                    c.drawText(label, nodeX[node], nodeY[node] + if (isActive) 27f else -10f, paint)
                }
            }

            // Reference-inspired clock/date placement, top-centre.
            val now = System.currentTimeMillis()
            val minute = now / 60_000L
            if (minute != lastClockMinute) {
                lastClockMinute = minute
                clockText = DateFormat.format("HH:mm", Date(now)).toString()
            }
            paint.textAlign = Paint.Align.CENTER
            paint.typeface = Typeface.create("serif", Typeface.NORMAL)
            paint.color = Color.argb(225, 255, 250, 252)
            paint.textSize = (w * .065f).coerceIn(34f, 56f)
            c.drawText(clockText, w * .5f, h * .19f, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.color = Color.argb(190, 255, 240, 245)
            paint.textSize = (w * .014f).coerceIn(9f, 13f)
            c.drawText(DateFormat.format("EEE, dd MMM", Date(now)).toString(), w * .5f, h * .145f, paint)

            paint.color = Color.argb(215, 255, 255, 255)
            paint.typeface = Typeface.create("serif", Typeface.BOLD)
            paint.textSize = (w * .055f).coerceIn(27f, 43f) * (1f + .035f * sin(t * 2.1f) + audio * .025f)
            c.drawText(fitWord(words.getOrNull(active).orEmpty(), paint, w * .86f), w * .5f, h * .73f, paint)
            paint.typeface = Typeface.create("sans-serif", Typeface.NORMAL)
            paint.color = Color.argb(160, 255, 238, 244)
            paint.textSize = (w * .019f).coerceIn(12f, 17f)
            c.drawText(fitWord(NeedleMusicState.artist.value, paint, w * .8f), w * .5f, h * .775f, paint)

            frame += .016f
        }

        private fun drawLightRibbon(c: Canvas, w: Float, h: Float, t: Float, phase: Float, color: Int, thickness: Float) {
            val drift = sin(t * .18f + phase) * w * .12f
            val y = h * (.29f + .19f * sin(t * .13f + phase))
            backgroundPath.reset()
            backgroundPath.moveTo(-w * .1f, y + drift * .15f)
            backgroundPath.cubicTo(w * .22f, y - thickness, w * .55f, y + thickness, w * 1.1f, y - drift * .12f)
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = thickness
            paint.strokeCap = Paint.Cap.ROUND
            paint.color = color
            c.drawPath(backgroundPath, paint)
            paint.strokeWidth = max(1f, thickness * .035f)
            paint.color = Color.argb(min(110, Color.alpha(color)), 255, 95, 125)
            c.drawPath(backgroundPath, paint)
            paint.style = Paint.Style.FILL
        }

        private fun drawGlint(c: Canvas, x: Float, y: Float, radius: Float, alpha: Int) {
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = .65f
            paint.color = Color.argb(alpha.coerceIn(0, 255), 255, 255, 255)
            c.drawLine(x - radius, y, x + radius, y, paint)
            c.drawLine(x, y - radius, x, y + radius, paint)
            paint.style = Paint.Style.FILL
        }

        private fun fitWord(value: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(value) <= maxWidth) return value
            var end = value.length
            while (end > 2 && p.measureText(value.substring(0, end) + "…") > maxWidth) end--
            return value.substring(0, end) + "…"
        }
    }
}