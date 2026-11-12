package com.korkmazdesign.needle

import android.os.Bundle
import android.os.Build
import android.Manifest
import android.content.pm.PackageManager
import android.content.Intent
import android.content.ComponentName
import android.app.WallpaperManager
import android.provider.Settings
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.clickable
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Button
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import android.os.SystemClock
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.nativeCanvas
import android.graphics.Paint as AndroidPaint
import android.graphics.Typeface as AndroidTypeface
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import kotlin.math.cos
import kotlin.math.sin

private val Ink = Color(0xFF080609)
private val Crimson = Color(0xFFFF3155)
private val SoftWhite = Color(0xFFF4EDF0)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS), 7101)
        }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 7102)
        } else {
            NeedleAudioReactive.start()
        }
        window.statusBarColor = android.graphics.Color.rgb(8, 6, 9)
        window.navigationBarColor = android.graphics.Color.rgb(8, 6, 9)
        intent?.data?.let { SpotifyDirect.handleRedirect(this, it) }
        setContent {
            NeedleScreen(
                initialClientId = SpotifyDirect.savedClientId(this).ifBlank { "1a6f153c4e9e432dbfd0de09a71d23c7" },
                onDirectConnect = { clientId -> SpotifyDirect.startAuthorization(this, clientId) },
                onConnectSpotify = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) },
                onSetWallpaper = {
                    val intent = Intent(WallpaperManager.ACTION_CHANGE_LIVE_WALLPAPER).apply {
                        putExtra(WallpaperManager.EXTRA_LIVE_WALLPAPER_COMPONENT,
                            ComponentName(this@MainActivity, NeedleLiveWallpaperService::class.java))
                    }
                    startActivity(intent)
                }
            )
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == 7102 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) NeedleAudioReactive.start()
    }

    override fun onDestroy() {
        NeedleAudioReactive.stop()
        super.onDestroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        intent.data?.let { SpotifyDirect.handleRedirect(this, it) }
    }
}

@Composable
fun NeedleScreen(
    onConnectSpotify: () -> Unit,
    onSetWallpaper: () -> Unit,
    onDirectConnect: (String) -> Unit,
    initialClientId: String
) {
    val context = LocalContext.current
    var clientId by remember { mutableStateOf(initialClientId) }
    var connectMessage by remember { mutableStateOf("Direct Spotify connection uses your own Client ID.") }
    var playbackPosition by remember { mutableStateOf(0L) }

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
            delay(5000)
        }
    }
    val trackTitle = NeedleMusicState.title.value
    val trackArtist = NeedleMusicState.artist.value

    LaunchedEffect(trackTitle, trackArtist) {
        if (trackTitle == "No track connected") return@LaunchedEffect
        NeedleMusicState.lyricsMessage.value = "Loading lyrics…"
        val timedText = withContext(Dispatchers.IO) {
            try {
                val query = "https://lrclib.net/api/get?track_name=" +
                    URLEncoder.encode(trackTitle, "UTF-8") + "&artist_name=" +
                    URLEncoder.encode(trackArtist, "UTF-8")
                val connection = (URL(query).openConnection() as HttpURLConnection).apply {
                    connectTimeout = 8000
                    readTimeout = 8000
                    setRequestProperty("User-Agent", "NEEDLE-Android/0.1")
                }
                try {
                    if (connection.responseCode != 200) null
                    else JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
                        .optString("syncedLyrics").takeIf { it.isNotBlank() && it != "null" }
                } finally { connection.disconnect() }
            } catch (_: Exception) { null }
        }
        if (timedText != null) {
            val parsed = Regex("""\[(\d{2}):(\d{2})[.](\d{2,3})\](.*)""")
                .findAll(timedText).mapNotNull { match ->
                    val minutes = match.groupValues[1].toLong()
                    val seconds = match.groupValues[2].toLong()
                    val millis = match.groupValues[3].padEnd(3, '0').take(3).toLong()
                    val words = match.groupValues[4].trim()
                    if (words.isBlank()) null else LyricLine(minutes * 60000 + seconds * 1000 + millis, words)
                }.toList()
            NeedleMusicState.lyrics.value = parsed
            NeedleMusicState.lyricsMessage.value = if (parsed.isEmpty()) "Timed lyrics unavailable for this track" else ""
        } else {
            NeedleMusicState.lyricsMessage.value = "Timed lyrics not found for this track"
        }
    }

    LaunchedEffect(trackTitle) {
        while (true) {
            val base = NeedleMusicState.positionMs.value
            val updated = NeedleMusicState.positionUpdatedAt.value
            playbackPosition = if (NeedleMusicState.playing.value && updated > 0L)
                base + (SystemClock.elapsedRealtime() - updated).coerceAtLeast(0L) else base
            delay(250)
        }
    }
    val lyricLines = NeedleMusicState.lyrics.value
    val activeLyric = lyricLines.indexOfLast { it.timeMs <= playbackPosition }

    val motion = rememberInfiniteTransition(label = "needle-motion")
    val lyricReveal by animateFloatAsState(targetValue = if (activeLyric >= 0) 1f else 0f, animationSpec = tween(1100), label = "lyric-reveal")
    val pulse = motion.animateFloat(
        initialValue = 0.82f, targetValue = 1.16f,
        animationSpec = infiniteRepeatable(tween(1900, easing = LinearEasing), RepeatMode.Reverse),
        label = "pulse"
    ).value

    MaterialTheme {
        Surface(Modifier.fillMaxSize(), color = Ink) {
            Box(Modifier.fillMaxSize()) {
                Canvas(Modifier.fillMaxSize()) {
                    drawRect(Brush.radialGradient(
                        colors = listOf(Color(0xFF48101F), Color(0xFF170B13), Ink),
                        center = Offset(size.width * .52f, size.height * .43f),
                        radius = size.maxDimension * .78f
                    ))
                    val words = lyricLines.getOrNull(activeLyric)?.text?.trim()
                        ?.split(Regex("\\s+"))?.filter { it.isNotBlank() }.orEmpty()
                    val nextLineTime = lyricLines.getOrNull(activeLyric + 1)?.timeMs
                    val line = lyricLines.getOrNull(activeLyric)
                    val duration = if (line != null && nextLineTime != null)
                        (nextLineTime - line.timeMs).coerceAtLeast(1000L) else 4000L
                    val progress = if (line != null)
                        ((playbackPosition - line.timeMs).toFloat() / duration).coerceIn(0f, .999f) else 0f
                    val activeWord = if (words.isNotEmpty())
                        (progress * words.size).toInt().coerceIn(0, words.lastIndex) else -1
                    val wordProgress = if (words.isNotEmpty())
                        (progress * words.size - activeWord).coerceIn(0f, 1f) else 0f
                    val density = drawContext.density
                    val w = size.width
                    val h = size.height
                    val count = maxOf(12, words.size)
                    val points = (0 until count).map { i ->
                        val x = w * (.045f + .91f * i / (count - 1).coerceAtLeast(1))
                        val wave = sin(i * 1.31f + pulse * 1.2f)
                        val y = h * (.49f + wave * .105f + cos(i * .63f + pulse) * .035f)
                        Offset(x, y)
                    }
                    // Deep, moving field of tiny stars fills the portrait screen.
                    for (i in 0 until 115) {
                        val x = ((i * 83f) % w)
                        val y = ((i * 137f) % h)
                        val twinkle = (.18f + .22f * (0.5f + 0.5f * sin(pulse * 2f + i))).coerceIn(.08f, .48f)
                        drawCircle(Color.White.copy(alpha = twinkle), if (i % 9 == 0) 1.8f else .9f, Offset(x, y))
                    }
                    val audio = NeedleAudioReactive.level.coerceIn(0f, 1f)
                    // A continuous horizontal constellation, with each word anchored to its own star.
                    for (i in 0 until points.lastIndex) {
                        val reveal = if (activeWord < 0) .12f else
                            if (i < activeWord) .72f else if (i == activeWord) wordProgress else .16f
                        drawLine(Color.White.copy(alpha = reveal * (.55f + audio * .35f)),
                            points[i], points[i + 1], strokeWidth = (1.0f + audio * 1.1f))
                    }
                    points.forEachIndexed { i, p ->
                        val isActive = i == activeWord
                        val radius = if (isActive) 3.2f + audio * 1.7f else 1.7f
                        drawCircle(Crimson.copy(alpha = if (isActive) .22f else .09f),
                            radius = if (isActive) 15f + audio * 6f else 5f, center = p)
                        drawCircle(Color.White.copy(alpha = if (isActive) 1f else .78f),
                            radius = radius, center = p)
                    }
                    val native = drawContext.canvas.nativeCanvas
                    val labelPaint = AndroidPaint(AndroidPaint.ANTI_ALIAS_FLAG).apply {
                        color = android.graphics.Color.argb(205, 255, 245, 249)
                        textAlign = AndroidPaint.Align.CENTER
                        typeface = AndroidTypeface.create("sans-serif", AndroidTypeface.NORMAL)
                        textSize = 10f * density
                    }
                    words.forEachIndexed { i, word ->
                        val p = points.getOrNull(i) ?: return@forEachIndexed
                        labelPaint.color = if (i == activeWord) android.graphics.Color.WHITE
                            else android.graphics.Color.argb(175, 255, 235, 242)
                        labelPaint.typeface = AndroidTypeface.create("sans-serif", if (i == activeWord) AndroidTypeface.BOLD else AndroidTypeface.NORMAL)
                        val emphasis = if (i == activeWord)
                            (1.0f + .48f * (0.5f + 0.5f * sin(pulse * 2.4f)) + audio * .18f)
                            else if (i == activeWord - 1 || i == activeWord + 1) 1.12f else .82f
                        labelPaint.textSize = (if (i == activeWord) 20f else 10f) * density * emphasis
                        val maxText = w / maxOf(4f, words.size.toFloat()) * .92f
                        var shown = word
                        while (shown.length > 2 && labelPaint.measureText(shown) > maxText) shown = shown.dropLast(1)
                        if (shown != word) shown += "…"
                        native.drawText(shown, p.x, p.y + (if (i == activeWord) 30f else 20f) * density, labelPaint)
                    }
                    // Draw the current word's constellation pulse and a short traveling spark.
                    if (activeWord >= 0) {
                        val p = points[activeWord]
                        val spark = (wordProgress * 5f).coerceIn(0f, 1f)
                        drawCircle(Crimson.copy(alpha = .22f + audio * .2f),
                            radius = 19f + 8f * sin(pulse * 2f), center = p)
                        if (activeWord < points.lastIndex) {
                            val next = points[activeWord + 1]
                            val travel = Offset(p.x + (next.x - p.x) * spark,
                                p.y + (next.y - p.y) * spark)
                            drawCircle(Color.White, radius = 2.8f, center = travel)
                        }
                    }
                }

                Column(
                    Modifier.fillMaxSize().padding(horizontal = 26.dp, vertical = 30.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
                        Text("N E E D L E", color = SoftWhite, fontSize = 13.sp, fontWeight = FontWeight.SemiBold, letterSpacing = 3.sp)
                        Text("BETA  01", color = Crimson, fontSize = 10.sp, letterSpacing = 2.sp)
                    }
                    Spacer(Modifier.weight(1f))
                    Text("NOW PLAYING", color = Crimson, fontSize = 10.sp, letterSpacing = 4.sp)
                    Spacer(Modifier.height(14.dp))
                    if (lyricLines.isNotEmpty()) {
                        val currentWords = lyricLines.getOrNull(activeLyric)?.text?.trim()?.split(Regex("\\s+")).orEmpty()
                        val nextTime = lyricLines.getOrNull(activeLyric + 1)?.timeMs
                        val currentLine = lyricLines.getOrNull(activeLyric)
                        val span = if (currentLine != null && nextTime != null) (nextTime - currentLine.timeMs).coerceAtLeast(1000L) else 4000L
                        val lineProgress = if (currentLine != null) ((playbackPosition - currentLine.timeMs).toFloat() / span).coerceIn(0f, .999f) else 0f
                        val wi = if (currentWords.isNotEmpty()) (lineProgress * currentWords.size).toInt().coerceIn(0, currentWords.lastIndex) else 0
                        Text(currentWords.getOrNull(wi).orEmpty(),
                            color = SoftWhite,
                            fontSize = (30f + 12f * (0.5f + 0.5f * sin(pulse * 2.4f)) + NeedleAudioReactive.level.coerceIn(0f, 1f) * 3f).sp,
                            lineHeight = 44.sp,
                            fontFamily = FontFamily.SansSerif, fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(8.dp))
                        Text("WORD IN CONSTELLATION", color = SoftWhite.copy(alpha = .48f), fontSize = 9.sp, letterSpacing = 2.sp)
                    } else {
                        Text(NeedleMusicState.lyricsMessage.value, color = SoftWhite.copy(alpha = .75f),
                            fontSize = 16.sp, textAlign = TextAlign.Center)
                    }
                    Spacer(Modifier.height(18.dp))
                    Text("SYNCED LYRICS • LRCLIB", color = SoftWhite.copy(alpha = .55f), fontSize = 9.sp, letterSpacing = 2.sp)
                    Spacer(Modifier.weight(1f))
                    Column(
                        Modifier.fillMaxWidth().background(Color(0xAA100B10), RoundedCornerShape(24.dp)).padding(20.dp)
                    ) {
                        Text(if (NeedleMusicState.connected.value) "SPOTIFY CONNECTED" else "SPOTIFY CONNECTION", color = Crimson, fontSize = 9.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(8.dp))
                        Text(NeedleMusicState.title.value, color = SoftWhite, fontSize = 17.sp, fontWeight = FontWeight.Medium)
                        Spacer(Modifier.height(5.dp))
                        Text(NeedleMusicState.artist.value, color = SoftWhite.copy(alpha = .55f), fontSize = 12.sp)
                        Spacer(Modifier.height(16.dp))
                        Box(Modifier.fillMaxWidth().height(3.dp).background(Color.White.copy(alpha = .12f), CircleShape)) {
                            Box(Modifier.fillMaxWidth(.34f).fillMaxHeight().background(Crimson, CircleShape))
                        }
                        Spacer(Modifier.height(16.dp))
                        Text("SPOTIFY CLIENT ID", color = Crimson, fontSize = 9.sp, letterSpacing = 2.sp)
                        Spacer(Modifier.height(6.dp))
                        OutlinedTextField(
                            value = clientId,
                            onValueChange = { clientId = it },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            label = { Text("Paste Client ID") }
                        )
                        Spacer(Modifier.height(8.dp))
                        Button(
                            onClick = {
                                try {
                                    onDirectConnect(clientId.trim())
                                    connectMessage = "Spotify authorization opened. Approve access in Spotify."
                                } catch (error: Exception) {
                                    connectMessage = error.message ?: "Could not start Spotify connection."
                                }
                            },
                            modifier = Modifier.fillMaxWidth()
                        ) { Text("CONNECT SPOTIFY ACCOUNT") }
                        Text(connectMessage, color = SoftWhite.copy(alpha = .6f), fontSize = 10.sp)
                        Spacer(Modifier.height(10.dp))
                        Text("NOTIFICATION ACCESS (BACKUP)", color = SoftWhite, fontSize = 9.sp,
                            letterSpacing = 1.sp, modifier = Modifier.clickable { onConnectSpotify() })
                        Spacer(Modifier.height(18.dp))
                        Text("SET NEEDLE LOCK SCREEN", color = SoftWhite, fontSize = 10.sp,
                            letterSpacing = 2.sp, modifier = Modifier.fillMaxWidth().clickable { onSetWallpaper() })
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
