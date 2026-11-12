package com.korkmazdesign.needle

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder
import java.security.KeyStore
import java.security.MessageDigest
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

object SpotifyDirect {
    private const val PREFS = "needle_spotify"
    private const val KEY_ALIAS = "needle_spotify_tokens"
    const val REDIRECT_URI = "needle://callback"
    private const val SCOPES = "user-read-currently-playing user-read-playback-state"

    data class Playback(
        val title: String,
        val artist: String,
        val progressMs: Long,
        val isPlaying: Boolean
    )

    fun savedClientId(context: Context): String =
        prefs(context).getString("client_id", "") ?: ""

    fun isConnected(context: Context): Boolean = readTokens(context) != null

    fun startAuthorization(context: Context, clientId: String) {
        require(clientId.isNotBlank()) { "Spotify Client ID is required." }
        prefs(context).edit().putString("client_id", clientId.trim()).apply()
        val verifier = randomUrlSafe(64)
        val state = randomUrlSafe(24)
        val challenge = Base64.encodeToString(
            MessageDigest.getInstance("SHA-256").digest(verifier.toByteArray(Charsets.US_ASCII)),
            Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP
        )
        prefs(context).edit()
            .putString("code_verifier", verifier)
            .putString("oauth_state", state)
            .apply()
        val uri = Uri.parse("https://accounts.spotify.com/authorize").buildUpon()
            .appendQueryParameter("client_id", clientId.trim())
            .appendQueryParameter("response_type", "code")
            .appendQueryParameter("redirect_uri", REDIRECT_URI)
            .appendQueryParameter("scope", SCOPES)
            .appendQueryParameter("state", state)
            .appendQueryParameter("code_challenge_method", "S256")
            .appendQueryParameter("code_challenge", challenge)
            .build()
        context.startActivity(Intent(Intent.ACTION_VIEW, uri).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
    }

    fun handleRedirect(context: Context, uri: Uri): Boolean {
        if (uri.scheme != "needle" || uri.host != "callback") return false
        val prefs = prefs(context)
        val expectedState = prefs.getString("oauth_state", null)
        val returnedState = uri.getQueryParameter("state")
        val code = uri.getQueryParameter("code")
        if (uri.getQueryParameter("error") != null || expectedState == null ||
            returnedState != expectedState || code.isNullOrBlank()) {
            prefs.edit().remove("code_verifier").remove("oauth_state").apply()
            return true
        }
        val verifier = prefs.getString("code_verifier", null) ?: return true
        val clientId = savedClientId(context)
        Thread {
            try {
                val body = formPost("https://accounts.spotify.com/api/token", mapOf(
                    "client_id" to clientId,
                    "grant_type" to "authorization_code",
                    "code" to code,
                    "redirect_uri" to REDIRECT_URI,
                    "code_verifier" to verifier
                ))
                saveTokens(context, body)
            } catch (_: Exception) {
                // The app remains usable through the notification-listener fallback.
            } finally {
                prefs.edit().remove("code_verifier").remove("oauth_state").apply()
            }
        }.start()
        return true
    }

    fun fetchCurrentPlayback(context: Context): Playback? {
        var tokens = readTokens(context) ?: return null
        if (tokens.optLong("expires_at") < System.currentTimeMillis() + 60_000L) {
            val refresh = tokens.optString("refresh_token")
            if (refresh.isBlank()) return null
            val refreshed = formPost("https://accounts.spotify.com/api/token", mapOf(
                "client_id" to savedClientId(context),
                "grant_type" to "refresh_token",
                "refresh_token" to refresh
            ))
            if (!refreshed.has("refresh_token")) refreshed.put("refresh_token", refresh)
            saveTokens(context, refreshed)
            tokens = refreshed
        }
        val connection = (URL("https://api.spotify.com/v1/me/player/currently-playing")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = 8000
            readTimeout = 8000
            setRequestProperty("Authorization", "Bearer ${tokens.getString("access_token")}")
        }
        try {
            if (connection.responseCode == 204) return null
            if (connection.responseCode !in 200..299) return null
            val json = JSONObject(connection.inputStream.bufferedReader().use { it.readText() })
            val item = json.optJSONObject("item") ?: return null
            val artists = item.optJSONArray("artists")
            val artist = if (artists != null && artists.length() > 0)
                artists.optJSONObject(0)?.optString("name").orEmpty() else "Spotify"
            return Playback(
                title = item.optString("name", "Unknown track"),
                artist = artist,
                progressMs = json.optLong("progress_ms", 0L),
                isPlaying = json.optBoolean("is_playing", false)
            )
        } finally {
            connection.disconnect()
        }
    }

    private fun formPost(endpoint: String, values: Map<String, String>): JSONObject {
        val payload = values.entries.joinToString("&") {
            URLEncoder.encode(it.key, "UTF-8") + "=" + URLEncoder.encode(it.value, "UTF-8")
        }
        val connection = (URL(endpoint).openConnection() as HttpURLConnection).apply {
            requestMethod = "POST"
            doOutput = true
            connectTimeout = 10000
            readTimeout = 10000
            setRequestProperty("Content-Type", "application/x-www-form-urlencoded")
        }
        try {
            connection.outputStream.use { it.write(payload.toByteArray(Charsets.UTF_8)) }
            val stream = if (connection.responseCode in 200..299) connection.inputStream else connection.errorStream
            val response = stream?.bufferedReader()?.use { it.readText() }.orEmpty()
            if (connection.responseCode !in 200..299) throw IllegalStateException("Spotify authorization failed: ${connection.responseCode}")
            val json = JSONObject(response)
            if (json.has("access_token")) {
                json.put("expires_at", System.currentTimeMillis() + json.optLong("expires_in", 3600L) * 1000L)
            }
            return json
        } finally {
            connection.disconnect()
        }
    }

    private fun saveTokens(context: Context, json: JSONObject) {
        val value = JSONObject()
            .put("access_token", json.optString("access_token"))
            .put("refresh_token", json.optString("refresh_token"))
            .put("expires_at", json.optLong("expires_at", System.currentTimeMillis() + 3600_000L))
        prefs(context).edit().putString("token_blob", encrypt(value.toString())).apply()
    }

    private fun readTokens(context: Context): JSONObject? = try {
        val blob = prefs(context).getString("token_blob", null) ?: return null
        JSONObject(decrypt(blob))
    } catch (_: Exception) { null }

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    private fun randomUrlSafe(size: Int): String {
        val bytes = ByteArray(size)
        SecureRandom().nextBytes(bytes)
        return Base64.encodeToString(bytes, Base64.URL_SAFE or Base64.NO_PADDING or Base64.NO_WRAP)
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        val existing = store.getKey(KEY_ALIAS, null) as? SecretKey
        if (existing != null) return existing
        val generator = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        generator.init(KeyGenParameterSpec.Builder(
            KEY_ALIAS,
            KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setRandomizedEncryptionRequired(true)
            .build())
        return generator.generateKey()
    }

    private fun encrypt(value: String): String {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secretKey())
        val encrypted = cipher.doFinal(value.toByteArray(Charsets.UTF_8))
        val combined = cipher.iv + encrypted
        return Base64.encodeToString(combined, Base64.NO_WRAP)
    }

    private fun decrypt(value: String): String {
        val combined = Base64.decode(value, Base64.NO_WRAP)
        val iv = combined.copyOfRange(0, 12)
        val encrypted = combined.copyOfRange(12, combined.size)
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, iv))
        return String(cipher.doFinal(encrypted), Charsets.UTF_8)
    }
}
