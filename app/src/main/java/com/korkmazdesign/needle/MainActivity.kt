package com.korkmazdesign.needle

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ComponentName
import android.app.WallpaperManager
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.Surface
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.viewinterop.AndroidView
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.os.SystemClock
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale

private val Ink = Color(0xFF080307)
private val Crimson = Color(0xFFFF3155)
private val SoftWhite = Color(0xFFFFF4F6)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        requestedOrientation = android.content.pm.ActivityInfo.SCREEN_ORIENTATION_SENSOR_LANDSCAPE
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7101)
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED)
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7102)
        else NeedleAudioReactive.start()
        window.statusBarColor = android.graphics.Color.BLACK
        window.navigationBarColor = android.graphics.Color.BLACK
        window.decorView.systemUiVisibility = (android.view.View.SYSTEM_UI_FLAG_FULLSCREEN
            or android.view.View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_STABLE
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
            or android.view.View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN)
        intent?.data?.let { SpotifyDirect.handleRedirect(this, it) }
        setContent {
            NeedleScreen(
                initialClientId = SpotifyDirect.savedClientId(this).ifBlank { "1a6f153c4e9e432dbfd0de09a71d23c7" },
                onDirectConnect = { SpotifyDirect.startAuthorization(this, it) },
                onConnectSpotify = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onSetWallpaper = {
                    startActivity(Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, NeedleLiveWallpaperService::class.java))
                    })
                }
            )
        }
    }
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7102 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) NeedleAudioReactive.start()
    }
    override fun onDestroy() { NeedleAudioReactive.stop(); super.onDestroy() }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent); setIntent(intent); intent.data?.let { SpotifyDirect.handleRedirect(this, it) }
    }
}

@Composable
fun NeedleScreen(onConnectSpotify: () -> Unit, onSetWallpaper: () -> Unit,
                 onDirectConnect: (String) -> Unit, initialClientId: String) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(initialClientId) }
    var showSettings by remember { mutableStateOf(false) }
    var playbackPosition by remember { mutableLongStateOf(0L) }
    var clock by remember { mutableStateOf(LocalDateTime.now()) }

    LaunchedEffect(Unit) {
        while (true) {
            val playback = withContext(Dispatchers.IO) {
                try { SpotifyDirect.fetchCurrentPlayback(context) } catch (_: Exception) { null }
            }
            if (playback != null) {
                NeedleMusicState.title.value = playback.title
                NeedleMusicState.artist.value = playback.artist
                NeedleMusicState.connected.value = true
                NeedleMusicState.positionMs.value = playback.progressMs
                NeedleMusicState.playing.value = playback.isPlaying
                NeedleMusicState.positionUpdatedAt.value = SystemClock.elapsedRealtime()
            }
            delay(2000)
        }
    }
    val title = NeedleMusicState.title.value
    val artist = NeedleMusicState.artist.value
    LaunchedEffect(title, artist) {
        if (title == "No track connected") return@LaunchedEffect
        NeedleMusicState.lyricsMessage.value = "Loading lyrics…"
        val raw = withContext(Dispatchers.IO) {
            try {
                val query = "https://lrclib.net/api/get?track_name=" + URLEncoder.encode(title, "UTF-8") +
                    "&artist_name=" + URLEncoder.encode(artist, "UTF-8")
                val c = (URL(query).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000; readTimeout = 8000; setRequestProperty("User-Agent", "NEEDLE-Android/1.0")
                }
                try {
                    if (c.responseCode != 200) null else JSONObject(c.inputStream.bufferedReader().use { it.readText() })
                        .optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                } finally { c.disconnect() }
            } catch (_: Exception) { null }
        }
        if (raw != null) {
            val parsed = Regex("""\[(\d{2}):(\d{2})[.](\d{2,3})\](.*)""").findAll(raw).mapNotNull { m ->
                val ms = m.groupValues[1].toLong() * 60000 + m.groupValues[2].toLong() * 1000 +
                    m.groupValues[3].padEnd(3, '0').take(3).toLong()
                m.groupValues[4].trim().takeIf { it.isNotBlank() }?.let { LyricLine(ms, it) }
            }.toList()
            NeedleMusicState.lyrics.value = parsed
            NeedleMusicState.lyricsMessage.value = if (parsed.isEmpty()) "Timed lyrics unavailable" else ""
        } else NeedleMusicState.lyricsMessage.value = "Timed lyrics not found"
    }
    LaunchedEffect(Unit) {
        while (true) {
            clock = LocalDateTime.now()
            val base = NeedleMusicState.positionMs.value
            val updated = NeedleMusicState.positionUpdatedAt.value
            val position = if (NeedleMusicState.playing.value && updated > 0L)
                base + (SystemClock.elapsedRealtime() - updated).coerceAtLeast(0L) else base
            playbackPosition = position
            NeedleGlState.frame = NeedleFrameFactory.create(NeedleMusicState.lyrics.value, position, NeedleAudioReactive.level)
            delay(16)
        }
    }

    val lines = NeedleMusicState.lyrics.value
    val activeLine = lines.indexOfLast { it.timeMs <= playbackPosition }
    val currentLine = lines.getOrNull(activeLine)
    val words = currentLine?.text?.trim()?.split(Regex("\\s+")).orEmpty()
    val nextTime = lines.getOrNull(activeLine + 1)?.timeMs
    val duration = if (currentLine != null && nextTime != null) (nextTime - currentLine.timeMs).coerceAtLeast(1000L) else 4000L
    val progress = if (currentLine != null) ((playbackPosition - currentLine.timeMs).toFloat() / duration).coerceIn(0f, .999f) else 0f
    val wordIndex = if (words.isNotEmpty()) (progress * words.size).toInt().coerceIn(0, words.lastIndex) else -1
    val activeWord = words.getOrNull(wordIndex).orEmpty()

    val motion = rememberInfiniteTransition(label = "ambient")
    val pulse by motion.animateFloat(.94f, 1.06f,
        infiniteRepeatable(tween(2100, easing = LinearEasing), RepeatMode.Reverse), label = "pulse")
    val date = clock.format(DateTimeFormatter.ofPattern("EEE, dd MMM", Locale.ENGLISH)).uppercase(Locale.ENGLISH)
    val time = clock.format(DateTimeFormatter.ofPattern("HH:mm"))

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize().background(Color(0xFF16040A))) {
                AndroidView(Modifier.fillMaxSize(), factory = { ctx ->
                    NeedleGlSurfaceView(ctx).apply { renderMode = android.opengl.GLSurfaceView.RENDERMODE_CONTINUOUSLY }
                }, update = { it.requestRender() })

                androidx.compose.foundation.Canvas(Modifier.fillMaxSize().padding(10.dp)) {
                    val stroke = 1.15.dp.toPx()
                    drawRoundRect(Color(0xCCFFF1F5), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                    drawRoundRect(Crimson.copy(alpha = .34f), style = androidx.compose.ui.graphics.drawscope.Stroke(stroke * 3f),
                        cornerRadius = androidx.compose.ui.geometry.CornerRadius(8.dp.toPx()))
                }
                Column(Modifier.align(Alignment.TopCenter).padding(top = 28.dp), horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(date, color = SoftWhite.copy(alpha = .78f), fontSize = 9.sp, letterSpacing = 2.4.sp)
                    Text(time, color = SoftWhite, fontSize = 34.sp, fontWeight = FontWeight.Light, letterSpacing = (-1).sp)
                }
                Column(Modifier.align(Alignment.TopEnd).padding(top = 30.dp, end = 34.dp), horizontalAlignment = Alignment.End) {
                    Text("✳ NEEDLE", color = SoftWhite, fontSize = 9.sp, letterSpacing = 1.8.sp, fontWeight = FontWeight.SemiBold)
                    Text(if (NeedleMusicState.connected.value) "MUSIC CONNECTED" else "WAITING FOR MUSIC",
                        color = SoftWhite.copy(alpha = .62f), fontSize = 7.sp, letterSpacing = 1.1.sp)
                }
                Column(Modifier.align(Alignment.BottomStart).padding(start = 34.dp, bottom = 28.dp).widthIn(max = 260.dp)) {
                    Text(title.takeIf { it != "No track connected" } ?: "NEEDLE", color = SoftWhite,
                        fontSize = 13.sp, fontWeight = FontWeight.SemiBold, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text(artist, color = SoftWhite.copy(alpha = .66f), fontSize = 10.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                    Text("LIVE LYRIC CONSTELLATION", color = SoftWhite.copy(alpha = .46f), fontSize = 7.sp, letterSpacing = 1.6.sp)
                }
                if (activeWord.isNotBlank()) {
                    Text(activeWord, Modifier.align(Alignment.Center).padding(horizontal = 24.dp),
                        color = SoftWhite, fontSize = (30f * pulse).sp, fontWeight = FontWeight.Bold,
                        textAlign = TextAlign.Center, letterSpacing = (-.8).sp)
                } else {
                    Text(NeedleMusicState.lyricsMessage.value.ifBlank { " " }, Modifier.align(Alignment.Center),
                        color = SoftWhite.copy(alpha = .55f), fontSize = 12.sp)
                }
                Text("@NEEDLEMACB", Modifier.align(Alignment.BottomEnd).padding(end = 34.dp, bottom = 30.dp),
                    color = SoftWhite.copy(alpha = .72f), fontSize = 8.sp, letterSpacing = 1.2.sp)
                Text("N", Modifier.align(Alignment.TopStart).padding(start = 34.dp, top = 28.dp)
                    .clickable { showSettings = true }, color = SoftWhite.copy(alpha = .8f), fontSize = 12.sp, fontWeight = FontWeight.Bold)

                if (showSettings) AlertDialog(
                    onDismissRequest = { showSettings = false }, containerColor = Color(0xFF170B12),
                    title = { Text("NEEDLE CONNECTION", color = SoftWhite) },
                    text = {
                        Column {
                            Text("Spotify Client ID", color = SoftWhite.copy(alpha = .7f), fontSize = 12.sp)
                            OutlinedTextField(value = clientId, onValueChange = { clientId = it }, singleLine = true, label = { Text("Client ID") })
                            Text("Notification access", color = SoftWhite.copy(alpha = .65f), fontSize = 11.sp,
                                modifier = Modifier.clickable { onConnectSpotify() }.padding(top = 12.dp))
                            Text("Set as live wallpaper", color = Crimson, fontSize = 12.sp,
                                modifier = Modifier.clickable { onSetWallpaper() }.padding(top = 12.dp))
                        }
                    },
                    confirmButton = { TextButton(onClick = {
                        try { onDirectConnect(clientId.trim()); showSettings = false } catch (_: Exception) { }
                    }) { Text("CONNECT", color = Crimson) } },
                    dismissButton = { TextButton(onClick = { showSettings = false }) { Text("CLOSE", color = SoftWhite) } }
                )
            }
        }
    }
}
