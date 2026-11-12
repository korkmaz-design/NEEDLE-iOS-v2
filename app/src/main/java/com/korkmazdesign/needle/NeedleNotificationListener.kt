package com.korkmazdesign.needle

import android.app.Notification
import android.service.notification.NotificationListenerService
import android.service.notification.StatusBarNotification
import androidx.compose.runtime.mutableStateOf

object NeedleMusicState {
    val title = mutableStateOf("No track connected")
    val artist = mutableStateOf("Connect Spotify to begin")
    val connected = mutableStateOf(false)
}

class NeedleNotificationListener : NotificationListenerService() {
    override fun onNotificationPosted(sbn: StatusBarNotification) {
        if (sbn.packageName != "com.spotify.music") return
        val extras = sbn.notification.extras ?: return
        val title = extras.getCharSequence(Notification.EXTRA_TITLE)?.toString()
        val artist = extras.getCharSequence(Notification.EXTRA_TEXT)?.toString()
            ?: extras.getCharSequence(Notification.EXTRA_SUB_TEXT)?.toString()

        if (!title.isNullOrBlank()) {
            NeedleMusicState.title.value = title
            NeedleMusicState.artist.value = artist ?: "Spotify"
            NeedleMusicState.connected.value = true
        }
    }

    override fun onNotificationRemoved(sbn: StatusBarNotification) {
        if (sbn.packageName == "com.spotify.music") {
            // Keep the last track visible; Spotify may briefly remove and repost its media notification.
        }
    }
}
