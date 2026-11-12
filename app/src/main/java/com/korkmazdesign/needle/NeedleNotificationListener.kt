package com.korkmazdesign.needle

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Intent
import android.media.session.MediaController
import android.media.session.MediaSession
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf
import androidx.core.app.NotificationCompat
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

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
    val audioLevel = mutableStateOf(0f)
}

class NeedleNotificationListener : NotificationListenerService() {
    private val handler = Handler(Looper.getMainLooper())
    private var mediaController: MediaController? = null
    private var lyricLines: List<LyricLine> = emptyList()
    private var currentTrack = ""
    private val notificationId = 7101
    private val channelId = "needle_lock_screen"

    private val lockScreenTicker = object : Runnable {
        override fun run() {
            val state = mediaController?.playbackState
            if (state != null) {
                NeedleMusicState.positionMs.value = state.position.coerceAtLeast(0L)
                NeedleMusicState.playing.value = state.state == android.media.session.PlaybackState.STATE_PLAYING
                NeedleMusicState.positionUpdatedAt.value = SystemClock.elapsedRealtime()
            }
            updateLockScreen()
            handler.postDelayed(this, 1000)
        }
    }

    override fun onListenerConnected() {
        super.onListenerConnected()
        createNotificationChannel()
        handler.removeCallbacks(lockScreenTicker)
        // Pick up Spotify if it was already playing before NEEDLE's listener connected.
        try {
            activeNotifications
                ?.filter { it.packageName == "com.spotify.music" }
                ?.forEach { onNotificationPosted(it) }
        } catch (_: SecurityException) {
            // Android may briefly withhold active notifications while the listener reconnects.
        }
        handler.post(lockScreenTicker)
    }

    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.spotify.music") return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString() ?: return
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString() ?: "Spotify"

        if (currentTrack != "$title|$artist") {
            currentTrack = "$title|$artist"
            lyricLines = emptyList()
            NeedleMusicState.title.value = title
            NeedleMusicState.artist.value = artist
            NeedleMusicState.lyrics.value = emptyList()
            NeedleMusicState.lyricsMessage.value = "Loading lyrics…"
            Thread {
                val found = fetchLyrics(title, artist)
                handler.post {
                    if (currentTrack == "$title|$artist") {
                        lyricLines = found
                        NeedleMusicState.lyrics.value = found
                        NeedleMusicState.lyricsMessage.value =
                            if (found.isEmpty()) "Timed lyrics not found for this track" else ""
                        updateLockScreen()
                    }
                }
            }.start()
        }
        NeedleMusicState.connected.value = true

        try {
            val token = if (Build.VERSION.SDK_INT >= 33) {
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION, MediaSession.Token::class.java)
            } else {
                @Suppress("DEPRECATION")
                extras.getParcelable(Notification.EXTRA_MEDIA_SESSION) as? MediaSession.Token
            }
            if (token != null) mediaController = MediaController(this, token)
        } catch (_: Exception) { }
    }

    private fun fetchLyrics(title: String, artist: String): List<LyricLine> {
        return try {
            val query = "https://lrclib.net/api/get?track_name=" +
                URLEncoder.encode(title, "UTF-8") + "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
            val connection = (URL(query).openConnection() as HttpURLConnection).apply {
                connectTimeout = 8000
                readTimeout = 8000
                setRequestProperty("User-Agent", "NEEDLE-Android/0.1")
            }
            try {
                if (connection.responseCode != 200) return emptyList()
                val text = JSONObject(connection.inputStream.bufferedReader().use { it.readText() }).optString("syncedLyrics")
                Regex("""\[(\d{2}):(\d{2})[.](\d{2,3})\](.*)""").findAll(text).mapNotNull { match ->
                    val min = match.groupValues[1].toLong()
                    val sec = match.groupValues[2].toLong()
                    val ms = match.groupValues[3].padEnd(3, '0').take(3).toLong()
                    val line = match.groupValues[4].trim()
                    if (line.isBlank()) null else LyricLine(min * 60000 + sec * 1000 + ms, line)
                }.toList()
            } finally { connection.disconnect() }
        } catch (_: Exception) { emptyList() }
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val manager = getSystemService(NotificationManager::class.java)
            val channel = NotificationChannel(channelId, "NEEDLE Lock Screen", NotificationManager.IMPORTANCE_LOW)
            channel.description = "Now playing and synchronized lyrics on the lock screen"
            channel.lockscreenVisibility = Notification.VISIBILITY_PUBLIC
            manager.createNotificationChannel(channel)
        }
    }

    private fun updateLockScreen() {
        val title = NeedleMusicState.title.value
        if (title == "No track connected") return
        val pos = NeedleMusicState.positionMs.value
        val active = lyricLines.indexOfLast { it.timeMs <= pos }
        val lyric = lyricLines.getOrNull(active)?.text ?: "Synchronized lyrics unavailable"
        val openApp = PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentTitle(title)
            .setContentText(lyric)
            .setSubText(NeedleMusicState.artist.value)
            .setStyle(NotificationCompat.BigTextStyle().bigText("$lyric ${NeedleMusicState.artist.value}"))
            .setVisibility(NotificationCompat.VISIBILITY_PUBLIC)
            .setCategory(NotificationCompat.CATEGORY_TRANSPORT)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setContentIntent(openApp)
            .build()
        getSystemService(NotificationManager::class.java).notify(notificationId, notification)
    }

    override fun onDestroy() {
        handler.removeCallbacks(lockScreenTicker)
        super.onDestroy()
    }
}