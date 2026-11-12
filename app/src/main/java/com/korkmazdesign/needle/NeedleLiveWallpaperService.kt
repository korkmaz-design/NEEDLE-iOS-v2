package com.korkmazdesign.needle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.wallpaper.WallpaperService
import android.view.SurfaceHolder
import kotlin.math.cos
import kotlin.math.sin

class NeedleLiveWallpaperService : WallpaperService() {
    override fun onCreateEngine(): Engine = NeedleEngine()

    private inner class NeedleEngine : Engine() {
        private val handler = Handler(Looper.getMainLooper())
        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private var visible = false
        private var frame = 0f
        private val tick = object : Runnable {
            override fun run() {
                drawFrame()
                if (visible) handler.postDelayed(this, 16)
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

        private fun drawFrame() {
            val holder = surfaceHolder
            var canvas: Canvas? = null
            try {
                canvas = holder.lockCanvas()
                if (canvas != null) render(canvas)
            } catch (_: Exception) {
            } finally {
                if (canvas != null) holder.unlockCanvasAndPost(canvas)
            }
        }

        private fun render(canvas: Canvas) {
            val w = canvas.width.toFloat()
            val h = canvas.height.toFloat()
            canvas.drawColor(Color.rgb(7, 5, 9))
            paint.shader = RadialGradient(w * .5f, h * .43f, h * .72f,
                intArrayOf(Color.rgb(78, 12, 34), Color.rgb(25, 8, 19), Color.rgb(7, 5, 9)),
                floatArrayOf(0f, .55f, 1f), Shader.TileMode.CLAMP)
            canvas.drawRect(0f, 0f, w, h, paint)
            paint.shader = null

            val audio = NeedleAudioReactive.level.coerceIn(0f, 1f)
            val current = NeedleMusicState.lyrics.value
            val basePosition = NeedleMusicState.positionMs.value
            val updatedAt = NeedleMusicState.positionUpdatedAt.value
            val pos = if (NeedleMusicState.playing.value && updatedAt > 0L)
                basePosition + (SystemClock.elapsedRealtime() - updatedAt).coerceAtLeast(0L)
            else basePosition
            val frameState = NeedleFrameFactory.create(current, pos, audio)
            val words = frameState.words
            val activeWord = frameState.activeWord
            val wordProgress = frameState.wordProgress
            val count = maxOf(12, words.size)
            val points = (0 until count).map { i ->
                val x = w * (.045f + .91f * i / (count - 1).coerceAtLeast(1))
                val y = h * (.49f + sin(i * 1.31f + frame * 1.2f) * .105f + cos(i * .63f + frame) * .035f)
                Pair(x, y)
            }
            for (i in 0 until 260) {
                val x = (i * 83f % w)
                val y = (i * 137f % h)
                val alpha = (45 + 55 * (0.5f + 0.5f * sin(frame * 2f + i))).toInt().coerceIn(20, 100)
                paint.style = Paint.Style.FILL
                paint.color = Color.argb(alpha, 255, 240, 246)
                canvas.drawCircle(x, y, if (i % 9 == 0) 1.8f else .9f, paint)
            }
            paint.style = Paint.Style.STROKE
            // Retain every completed constellation edge until the whole phrase is drawn.
            if (activeWord >= 0) {
                val reveal = ((activeWord + wordProgress) / (count - 1).toFloat()).coerceIn(0f, 1f)
                val fade = if (reveal > .94f) ((1f - reveal) / .06f).coerceIn(0f, 1f) else 1f
                for (edge in 0 until count - 1) {
                    val from = points[edge]
                    val to = points[edge + 1]
                    val progress = when { edge < activeWord -> 1f; edge == activeWord -> wordProgress; else -> 0f }
                    paint.color = Color.argb(((if (edge <= activeWord) 215 else 0) * fade).toInt().coerceIn(0, 255), 255, 125, 155)
                    paint.strokeWidth = 1.3f + audio * 1.8f
                    canvas.drawLine(from.first, from.second, from.first + (to.first - from.first) * progress, from.second + (to.second - from.second) * progress, paint)
                }
                // Expanding touch-wave rings pulse from the active lyric node.
                val origin = points[activeWord]
                for (ring in 0 until 3) {
                    val phase = (wordProgress + ring * .34f) % 1f
                    paint.color = Color.argb(((1f - phase) * 125f).toInt(), 255, 75, 115)
                    paint.strokeWidth = 1.5f
                    canvas.drawCircle(origin.first, origin.second, 8f + phase * h * .10f, paint)
                }
            }
            paint.style = Paint.Style.FILL
            points.forEachIndexed { i, p ->
                val activePoint = i == activeWord
                paint.color = Color.argb(if (activePoint) 75 else 25, 255, 49, 85)
                canvas.drawCircle(p.first, p.second, if (activePoint) 17f + audio * 5f else 5f, paint)
                paint.color = Color.argb(if (activePoint) 255 else 195, 255, 250, 252)
                canvas.drawCircle(p.first, p.second, if (activePoint) 3.2f + audio * 1.5f else 1.7f, paint)
            }
            paint.textAlign = Paint.Align.CENTER
            words.forEachIndexed { i, word ->
                val p = points.getOrNull(i) ?: return@forEachIndexed
                paint.color = if (i == activeWord) Color.WHITE else Color.argb(185, 255, 235, 242)
                paint.typeface = Typeface.create("sans-serif", if (i == activeWord) Typeface.BOLD else Typeface.NORMAL)
                val emphasis = if (i == activeWord)
                    1.0f + .48f * (0.5f + 0.5f * sin(frame * 2.4f)) + audio * .18f
                    else if (i == activeWord - 1 || i == activeWord + 1) 1.12f else .82f
                paint.textSize = (if (i == activeWord) (w * .045f).coerceIn(19f, 31f)
                    else (w * .023f).coerceIn(10f, 16f)) * emphasis
                var shown = word
                val maxText = w / maxOf(4f, words.size.toFloat()) * .92f
                while (shown.length > 2 && paint.measureText(shown) > maxText) shown = shown.dropLast(1)
                if (shown != word) shown += "…"
                canvas.drawText(shown, p.first, p.second + if (i == activeWord) 31f else 19f, paint)
            }
            if (activeWord >= 0 && activeWord < points.lastIndex) {
                val p = points[activeWord]
                val next = points[activeWord + 1]
                paint.color = Color.WHITE
                canvas.drawCircle(p.first + (next.first - p.first) * wordProgress,
                    p.second + (next.second - p.second) * wordProgress, 2.8f, paint)
            }
            paint.color = Color.argb(135, 255, 255, 255)
            for (i in 0 until 65) {
                val x = (i * 97f % w)
                val y = (i * 173f % h)
                canvas.drawCircle(x, y, if (i % 4 == 0) 1.4f else .8f, paint)
            }

            paint.color = Color.argb(225, 248, 239, 243)
            paint.textAlign = Paint.Align.CENTER
            paint.textSize = 17f
            canvas.drawText("N E E D L E", w * .5f, h * .16f, paint)
            paint.color = Color.argb(190, 255, 49, 85)
            paint.textSize = 10f
            canvas.drawText("MUSIC IN MOTION", w * .5f, h * .19f, paint)

            paint.color = Color.WHITE
            paint.typeface = Typeface.create("sans-serif", Typeface.BOLD)
            paint.textSize = (w * .045f).coerceIn(34f, 54f) * (1.0f + .18f * (0.5f + 0.5f * sin(frame * 2.4f)) + audio * .08f)
            canvas.drawText(ellipsize(words.getOrNull(activeWord).orEmpty(), paint, w * .90f), w * .5f, h * .69f, paint)
            paint.color = Color.argb(170, 230, 210, 220)
            paint.textSize = (w * .027f).coerceIn(22f, 34f)
            canvas.drawText(ellipsize(NeedleMusicState.artist.value, paint, w * .82f), w * .5f, h * .735f, paint)
            paint.color = Color.argb(120, 255, 255, 255)
            paint.textSize = 9f
            canvas.drawText("A NEEDLE EXPERIENCE", w * .5f, h * .91f, paint)
            frame += .0225f
        }

        private fun ellipsize(value: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(value) <= maxWidth) return value
            var result = value
            while (result.isNotEmpty() && p.measureText("$result…") > maxWidth) result = result.dropLast(1)
            return "$result…"
        }
    }
}