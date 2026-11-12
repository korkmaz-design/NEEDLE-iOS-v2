package com.korkmazdesign.needle

import android.media.audiofx.Visualizer
import android.util.Log
import kotlin.math.sqrt

/**
 * Captures the mixed device output through Android's Visualizer session 0.
 * Availability depends on Android/Samsung audio-capture policy and RECORD_AUDIO permission.
 */
object NeedleAudioReactive {
    private var visualizer: Visualizer? = null
    @Volatile private var smoothed = 0f

    val level: Float get() = smoothed

    @Synchronized
    fun start() {
        if (visualizer != null) return
        try {
            val effect = Visualizer(0)
            effect.captureSize = Visualizer.getCaptureSizeRange()[1]
            effect.setDataCaptureListener(object : Visualizer.OnDataCaptureListener {
                override fun onWaveFormDataCapture(v: Visualizer, waveform: ByteArray, samplingRate: Int) {
                    var sum = 0.0
                    for (sample in waveform) {
                        val centered = (sample.toInt() and 0xff) - 128
                        sum += centered.toDouble() * centered
                    }
                    val rms = sqrt(sum / waveform.size).toFloat() / 128f
                    smoothed = (smoothed * .72f + (rms * 2.8f).coerceIn(0f, 1f) * .28f)
                    NeedleMusicState.audioLevel.value = smoothed
                }
                override fun onFftDataCapture(v: Visualizer, fft: ByteArray, samplingRate: Int) {
                    // Waveform RMS drives the live pulse; FFT callback is retained for future band mapping.
                }
            }, Visualizer.getMaxCaptureRate() / 2, true, false)
            effect.enabled = true
            visualizer = effect
        } catch (e: Exception) {
            Log.w("NEEDLE", "Audio visualizer unavailable", e)
            visualizer?.release()
            visualizer = null
        }
    }

    @Synchronized
    fun stop() {
        try { visualizer?.enabled = false } catch (_: Exception) {}
        try { visualizer?.release() } catch (_: Exception) {}
        visualizer = null
        smoothed = 0f
        NeedleMusicState.audioLevel.value = 0f
    }
}
