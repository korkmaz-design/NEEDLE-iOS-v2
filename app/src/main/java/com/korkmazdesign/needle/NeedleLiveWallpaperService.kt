package com.korkmazdesign.needle

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import android.graphics.Typeface
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

            val audio = NeedleAudioReactive.level
            val current = NeedleMusicState.lyrics.value
            val pos = NeedleMusicState.positionMs.value
            val active = current.indexOfLast { it.timeMs <= pos }
            val line = current.getOrNull(active)
            val nextTime = current.getOrNull(active + 1)?.timeMs
            val duration = if (line != null && nextTime != null) (nextTime - line.timeMs).coerceAtLeast(1000L) else 4000L
            val progress = if (line != null) ((pos - line.timeMs).toFloat() / duration).coerceIn(0f, .999f) else 0f
            val words = line?.text?.trim()?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
            val wordIndex = if (words.isNotEmpty()) (progress * words.size).toInt().coerceIn(0, words.lastIndex) else 0
            val wordProgress = if (words.isNotEmpty()) (progress * words.size - wordIndex).coerceIn(0f, 1f) else 0f
            val centerX = w * .5f + ((wordIndex - (words.size - 1) / 2f) * 23f).coerceIn(-w * .34f, w * .34f)
            val centerY = h * .43f
            val offsets = listOf(-13f to 4f, -4f to -11f, 8f to -5f, 14f to 10f, -2f to 14f)
            val nodes = offsets.mapIndexed { i, o ->
                Pair(centerX + o.first + sin(frame + i) * (1.1f + audio * 1.5f),
                    centerY + o.second + cos(frame + i) * 1.2f)
            }
            val links = listOf(0 to 1, 1 to 2, 2 to 3, 3 to 4, 4 to 0)
            val revealed = (wordProgress * (links.size + 1)).toInt().coerceIn(0, links.size)
            paint.strokeWidth = .7f
            paint.color = Color.argb(190, 255, 49, 85)
            links.take(revealed).forEach { (a, b) ->
                canvas.drawLine(nodes[a].first, nodes[a].second, nodes[b].first, nodes[b].second, paint)
            }
            nodes.forEachIndexed { i, p ->
                if (i <= revealed) {
                    paint.color = Color.argb(235, 255, 250, 252)
                    canvas.drawCircle(p.first, p.second, 1.1f + audio * .4f, paint)
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
            canvas.drawText("N E E D L E", w * .5f, h * .16f, paint)
            paint.color = Color.argb(190, 255, 49, 85)
            paint.textSize = 10f
            canvas.drawText("MUSIC IN MOTION", w * .5f, h * .19f, paint)

            val current = NeedleMusicState.lyrics.value
            val pos = NeedleMusicState.positionMs.value
            val active = current.indexOfLast { it.timeMs <= pos }
            val line = current.getOrNull(active)?.text ?: NeedleMusicState.title.value.takeIf { it != "No track connected" } ?: "THE NIGHT IS STILL YOUNG"
            paint.color = Color.WHITE
            paint.typeface = Typeface.create("serif", Typeface.NORMAL)
            paint.textSize = 34f
            canvas.drawText(ellipsize(line, paint, w * .84f), w * .5f, h * .69f, paint)
            paint.color = Color.argb(170, 230, 210, 220)
            paint.textSize = 17f
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