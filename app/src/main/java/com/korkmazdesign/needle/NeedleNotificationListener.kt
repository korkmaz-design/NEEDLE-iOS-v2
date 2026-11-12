package com.korkmazdesign.needle

import android.app.Notification
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf

data class LyricLine(val timeMs: Long, val text: String)

object NeedleMusicState {
    val title = mutableStateOf("No track connected")
    val artist = mutableStateOf("Connect Spotify to begin")
    val connected = mutableStateOf(false)
    val lyrics = mutableStateOf<List<LyricLine>>(emptyList())
    val lyricsMessage = mutableStateOf("Waiting for a song…")
    val positionMs = mutableStateOf(0L)
    val playing = mutableStateOf(false)
    val positionUpdatedAt = mutableStateOf(0L)
}

class NeedleNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.spotify.music") return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!title.isNullOrBlank()) {
            if (NeedleMusicState.title.value != title || NeedleMusicState.artist.value != (artist ?: "Spotify")) {
                NeedleMusicState.title.value = title
                NeedleMusicState.artist.value = artist ?: "Spotify"
                NeedleMusicState.lyrics.value = emptyList()
                NeedleMusicState.lyricsMessage.value = "Loading lyrics…"
            }
            NeedleMusicState.connected.value = true
        }

        try {
            val token = if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            if (token != null) {
                val state = MediaController(this, token).playbackState
                if (state != null) {
                    NeedleMusicState.positionMs.value = state.position.coerceAtLeast(0L)
                    NeedleMusicState.playing.value = state.state == android.media.session.PlaybackState.STATE_PLAYING
                    NeedleMusicState.positionUpdatedAt.value = SystemClock.elapsedRealtime()
                }
            }
        } catch (_: Exception) { }
    }
}