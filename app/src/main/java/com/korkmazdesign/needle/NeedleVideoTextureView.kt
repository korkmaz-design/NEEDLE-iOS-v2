package com.korkmazdesign.needle

import android.content.Context
import android.graphics.SurfaceTexture
import android.media.MediaPlayer
import android.view.Surface
import android.view.TextureView

/**
 * Looping video layer used beneath the transparent OpenGL constellation.
 * The source is bundled in app/src/main/assets so playback works offline.
 */
class NeedleVideoTextureView(context: Context) : TextureView(context), TextureView.SurfaceTextureListener {
    private var player: MediaPlayer? = null
    private var videoSurface: Surface? = null

    init {
        surfaceTextureListener = this
        isOpaque = false
    }

    override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
        startVideo(surface)
    }

    override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) = Unit

    override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
        releasePlayer()
        return true
    }

    override fun onSurfaceTextureUpdated(surface: SurfaceTexture) = Unit

    private fun startVideo(texture: SurfaceTexture) {
        releasePlayer()
        try {
            val descriptor = context.assets.openFd("needle_background_clean_final.mp4")
            val output = Surface(texture)
            videoSurface = output
            val media = MediaPlayer()
            player = media
            media.setDataSource(descriptor.fileDescriptor, descriptor.startOffset, descriptor.length)
            descriptor.close()
            media.setSurface(output)
            media.isLooping = true
            media.setVideoScalingMode(MediaPlayer.VIDEO_SCALING_MODE_SCALE_TO_FIT_WITH_CROPPING)
            media.setOnPreparedListener { it.start() }
            media.setOnErrorListener { _, _, _ ->
                releasePlayer()
                true
            }
            media.prepareAsync()
        } catch (_: Exception) {
            releasePlayer()
        }
    }

    private fun releasePlayer() {
        try { player?.setOnPreparedListener(null); player?.setOnErrorListener(null); player?.release() } catch (_: Exception) { }
        player = null
        try { videoSurface?.release() } catch (_: Exception) { }
        videoSurface = null
    }
}
