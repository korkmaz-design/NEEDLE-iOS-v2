package com.korkmazdesign.needle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.os.Handler
import android.os.Looper
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
                if (visible) handler.postDelayed(this, 50)
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

            val points = ArrayList<Pair<Float, Float>>()
            for (i in 0 until 46) {
                val angle = i * 2.399f
                val radius = (34f + (i % 8) * 23f) * (1f + .035f * sin(frame + i))
                val x = w * .5f + cos(angle) * radius
                val y = h * .43f + sin(angle) * radius * 1.45f
                points.add(Pair(x, y))
            }
            paint.strokeWidth = 1.1f
            for (i in points.indices) {
                for (j in i + 1 until points.size) {
                    val a = points[i]
                    val b = points[j]
                    val dx = a.first - b.first
                    val dy = a.second - b.second
                    if (dx * dx + dy * dy < 115f * 115f) {
                        paint.color = Color.argb(58, 255, 45, 83)
                        canvas.drawLine(a.first, a.second, b.first, b.second, paint)
                    }
                }
            }
            points.forEachIndexed { i, p ->
                paint.color = if (i % 5 == 0) Color.WHITE else Color.argb(190, 255, 225, 234)
                canvas.drawCircle(p.first, p.second, if (i % 5 == 0) 3.2f else 1.7f, paint)
                if (i % 5 == 0) {
                    paint.color = Color.argb(42, 255, 45, 83)
                    canvas.drawCircle(p.first, p.second, 10f + 2f * sin(frame), paint)
                }
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
            paint.letterSpacing = .28f
            canvas.drawText("N E E D L E", w * .5f, h * .16f, paint)
            paint.color = Color.argb(190, 255, 49, 85)
            paint.textSize = 10f
            canvas.drawText("MUSIC IN MOTION", w * .5f, h * .19f, paint)

            val current = NeedleMusicState.lyrics.value
            val pos = NeedleMusicState.positionMs.value
            val active = current.indexOfLast { it.timeMs <= pos }
            val line = current.getOrNull(active)?.text ?: NeedleMusicState.title.value.takeIf { it != "No track connected" } ?: "THE NIGHT IS STILL YOUNG"
            paint.color = Color.WHITE
            paint.textSize = 23f
            canvas.drawText(ellipsize(line, paint, w * .84f), w * .5f, h * .69f, paint)
            paint.color = Color.argb(170, 230, 210, 220)
            paint.textSize = 13f
            canvas.drawText(ellipsize(NeedleMusicState.artist.value, paint, w * .75f), w * .5f, h * .73f, paint)
            paint.color = Color.argb(120, 255, 255, 255)
            paint.textSize = 9f
            canvas.drawText("A NEEDLE EXPERIENCE", w * .5f, h * .91f, paint)
            frame += .045f
        }

        private fun ellipsize(value: String, p: Paint, maxWidth: Float): String {
            if (p.measureText(value) <= maxWidth) return value
            var result = value
            while (result.isNotEmpty() && p.measureText("$result…") > maxWidth) result = result.dropLast(1)
            return "$result…"
        }
    }
}