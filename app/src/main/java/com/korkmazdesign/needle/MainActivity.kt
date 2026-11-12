package com.korkmazdesign.needle

import android.os.Bundle
import android.content.Intent
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
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
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.setValue
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
import androidx.compose.ui.text.font.FontWeight
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
        window.statusBarColor = android.graphics.Color.rgb(8, 6, 9)
        window.navigationBarColor = android.graphics.Color.rgb(8, 6, 9)
        setContent { NeedleScreen(onConnectSpotify = { startActivity(Intent(Settings.ACTION_NOTIFICATION_LISTENER_SETTINGS)) }) }
    }
}

@Composable
fun NeedleScreen(onConnectSpotify: () -> Unit) {
    var playbackPosition by remember { mutableStateOf(0L) }
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
                    val cx = size.width * .5f
                    val cy = size.height * .43f
                    val points = (0 until 34).map { i ->
                        val angle = i * 2.399
                        val radius = (34 + (i % 7) * 21) * pulse
                        Offset(cx + cos(angle).toFloat() * radius, cy + sin(angle).toFloat() * radius * 1.15f)
                    }
                    points.forEachIndexed { i, p ->
                        points.drop(i + 1).filter { q ->
                            val dx = p.x - q.x; val dy = p.y - q.y
                            dx * dx + dy * dy < 105f * 105f
                        }.forEach { q ->
                            drawLine(Crimson.copy(alpha = .23f), p, q, strokeWidth = 1.1f)
                        }
                        drawCircle(Color.White.copy(alpha = .85f), radius = if (i % 5 == 0) 3.2f else 1.7f, center = p)
                        if (i % 5 == 0) drawCircle(Crimson.copy(alpha = .22f), radius = 9f * pulse, center = p)
                    }
                    for (i in 0 until 54) {
                        val x = (i * 97f % size.width)
                        val y = (i * 173f % size.height)
                        drawCircle(Color.White.copy(alpha = if (i % 3 == 0) .42f else .16f), 1f, Offset(x, y))
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
                        Text(lyricLines.getOrNull(activeLyric)?.text ?: lyricLines.first().text,
                            color = SoftWhite, fontSize = 27.sp, lineHeight = 35.sp,
                            fontWeight = FontWeight.Light, textAlign = TextAlign.Center)
                        Spacer(Modifier.height(12.dp))
                        Text(lyricLines.getOrNull(activeLyric + 1)?.text.orEmpty(),
                            color = SoftWhite.copy(alpha = .42f), fontSize = 17.sp,
                            lineHeight = 23.sp, textAlign = TextAlign.Center)
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
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.CenterVertically) {
                            Text("CONNECT SPOTIFY", color = SoftWhite, fontSize = 10.sp, letterSpacing = 2.sp, modifier = Modifier.clickable { onConnectSpotify() })
                            Text("DESIGNED FOR S24 ULTRA", color = SoftWhite.copy(alpha = .4f), fontSize = 8.sp, letterSpacing = 1.sp)
                        }
                    }
                    Spacer(Modifier.height(12.dp))
                }
            }
        }
    }
}
