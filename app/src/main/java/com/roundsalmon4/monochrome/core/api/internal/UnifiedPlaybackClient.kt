package com.roundsalmon4.monochrome.core.api.internal

import android.util.Base64
import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import com.roundsalmon4.monochrome.core.util.StringUtil
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class UnifiedPlaybackClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val monochromeSessionRefresher: MonochromeSessionRefresher,
    private val prefs: PlayerPreferences
) {
    companion object {
        private const val TAG = "ChromePlayer-Unified"
        private const val API_BASE = "https://music-api.geeked.wtf"
        private const val API_TOKEN = "amp_29b2lIr4mze4tK-P8QDOxfMZ9anCgJ9_uGTUks3nIyo"
        private const val JWT_BUFFER_MS = 60_000L
    }

    private val gson = Gson()
    private val jwtMutex = Mutex()

    @Volatile
    var wasNotFound: Boolean = false

    suspend fun getStreamUrl(
        title: String,
        artist: String,
        isrc: String = "",
        durationMs: Long = 0L
    ): MonochromeStreamResult? {
        wasNotFound = false
        val jwt = getValidJwt() ?: run { Log.w(TAG, "No unified JWT"); return null }

        val params = buildString {
            append("track=").append(java.net.URLEncoder.encode(title, "UTF-8"))
            if (artist.isNotBlank()) append("&artist=").append(java.net.URLEncoder.encode(artist, "UTF-8"))
            if (isrc.isNotBlank()) append("&isrc=").append(java.net.URLEncoder.encode(isrc, "UTF-8"))
            if (durationMs > 0) append("&duration=").append(durationMs / 1000L)
            append("&quality=HI_RES_LOSSLESS")
            append("&intent=stream")
        }
        val request = Request.Builder()
            .url("$API_BASE/api/v2/track/?$params")
            .header("Authorization", "Bearer $API_TOKEN")
            .header("X-Turnstile-JWT", jwt)
            .header("Accept", "application/json")
            .build()

        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        if (resp.code == 401 || resp.code == 428) {
            Log.w(TAG, "Unified JWT rejected (${resp.code})")
            prefs.setUnifiedJwt("", 0L)
            return null
        }
        if (resp.code == 429) { Log.w(TAG, "Unified Playback rate limited"); return null }
        if (resp.code == 404 || resp.code == 502) {
            wasNotFound = true
            Log.w(TAG, "Unified Playback: track not found")
            return null
        }
        if (!resp.isSuccessful) { Log.w(TAG, "Unified Playback lookup: HTTP ${resp.code}"); return null }

        val envelope = resp.body?.string()?.let {
            runCatching {
                gson.fromJson<Map<String, Any?>>(it, object : TypeToken<Map<String, Any?>>() {}.type)
            }.getOrNull()
        } ?: run { Log.w(TAG, "Unified Playback: empty/invalid lookup"); return null }

        val playback = envelope["playback"] as? List<*> ?: run {
            Log.w(TAG, "Unified Playback: no playback array")
            return null
        }
        val resource = playback.firstNotNullOfOrNull { element ->
            val r = element as? Map<String, Any?> ?: return@firstNotNullOfOrNull null
            val url = r["url"]?.toString()?.takeIf { it.isNotBlank() } ?: return@firstNotNullOfOrNull null
            val kind = r["kind"]?.toString()
            if (kind == "audio" || kind == "manifest") r else null
        } ?: run { Log.w(TAG, "Unified Playback: no usable resource"); return null }

        val resourceUrl = resource["url"]?.toString() ?: ""
        val delivery = resource["delivery"]?.toString() ?: ""
        val mimeType = resource["mime_type"]?.toString() ?: "audio/flac"
        val source = resource["source"]?.toString() ?: envelope["selected_source"]?.toString() ?: "unified"

        // Verify the returned track matches what we asked for (prevents wrong-track from label metadata errors)
        val returnedTrack = envelope["track"] as? Map<*, *>
        val returnedIsrc = returnedTrack?.get("isrc")?.toString() ?: ""
        val returnedTitle = returnedTrack?.get("title")?.toString() ?: ""
        if (isrc.isNotBlank() && returnedIsrc.isNotBlank() && !isrc.equals(returnedIsrc, ignoreCase = true)) {
            Log.w(TAG, "Unified Playback: ISRC mismatch (requested=$isrc, got=$returnedIsrc), rejecting")
            return null
        }
        if (title.isNotBlank() && returnedTitle.isNotBlank() && !StringUtil.titlesMatch(title, returnedTitle)) {
            Log.w(TAG, "Unified Playback: title mismatch (requested=$title, got=$returnedTitle), rejecting")
            return null
        }

        if (delivery == "direct" || !isManifestUrl(resourceUrl, delivery, mimeType)) {
            Log.d(TAG, "Unified Playback: direct stream from $source")
            return MonochromeStreamResult(url = resourceUrl, mimeType = mimeType)
        }

        val asin = (envelope["track"] as? Map<*, *>)?.get("id")?.toString()
            ?: run { Log.w(TAG, "Unified Playback: manifest but no ASIN"); return null }
        Log.d(TAG, "Unified Playback: manifest from $source, trying server-side decrypt (asin=$asin)")
        return getDecryptedStream(asin)
    }

    private suspend fun getDecryptedStream(asin: String): MonochromeStreamResult? {
        val request = Request.Builder()
            .url("$API_BASE/api/stream/$asin/decrypt?quality=HD")
            .header("Authorization", "Bearer $API_TOKEN")
            .header("Accept", "application/json")
            .build()
        return try {
            val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
            if (!resp.isSuccessful) {
                Log.w(TAG, "Unified Playback decrypt: HTTP ${resp.code}")
                return null
            }
            val body = resp.body?.string()
            val data = body?.let {
                runCatching {
                    gson.fromJson<Map<String, Any?>>(it, object : TypeToken<Map<String, Any?>>() {}.type)
                }.getOrNull()
            }
            val url = data?.get("url")?.toString()?.takeIf { it.isNotBlank() }
            if (url != null) {
                Log.d(TAG, "Unified Playback: got decrypted stream")
                return MonochromeStreamResult(url = url, mimeType = data["mime_type"]?.toString() ?: "audio/flac")
            }
            Log.w(TAG, "Unified Playback decrypt: no url in response (${body?.take(200)})")
            null
        } catch (e: Exception) {
            Log.w(TAG, "Unified Playback decrypt failed: ${e.message}")
            null
        }
    }

    private fun isManifestUrl(url: String, delivery: String, mimeType: String): Boolean =
        delivery == "dash" || delivery == "hls" ||
            mimeType.contains("dash") || mimeType.contains("mpegurl") ||
            url.contains(".mpd") || url.contains(".m3u8")

    private suspend fun getValidJwt(): String? {
        val (cached, expiry) = prefs.getUnifiedJwt() ?: Pair("", 0L)
        if (cached.isNotBlank() && expiry > System.currentTimeMillis() + JWT_BUFFER_MS) return cached
        return jwtMutex.withLock {
            val (again, againExpiry) = prefs.getUnifiedJwt() ?: Pair("", 0L)
            if (again.isNotBlank() && againExpiry > System.currentTimeMillis() + JWT_BUFFER_MS) return again
            val turnstileToken = monochromeSessionRefresher.obtainTurnstileToken() ?: return null
            exchangeTokenForJwt(turnstileToken)
        }
    }

    private suspend fun exchangeTokenForJwt(token: String): String? {
        val body = "{\"cf_turnstile_response\":${gson.toJson(token)}}"
        val request = Request.Builder()
            .url("$API_BASE/api/auth/turnstile")
            .header("Authorization", "Bearer $API_TOKEN")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()
        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        if (!resp.isSuccessful) {
            Log.w(TAG, "Unified auth/turnstile: HTTP ${resp.code}")
            return null
        }
        val data = resp.body?.string()?.let {
            runCatching {
                gson.fromJson<Map<String, Any?>>(it, object : TypeToken<Map<String, Any?>>() {}.type)
            }.getOrNull()
        } ?: return null
        val jwt = data["access_token"]?.toString()
            ?: data["jwt"]?.toString()
            ?: data["token"]?.toString()
        if (jwt.isNullOrBlank()) { Log.w(TAG, "Unified auth: no JWT in response"); return null }
        val expiry = jwtExpiryMillis(jwt).takeIf { it > 0 }
            ?: (System.currentTimeMillis() + 55 * 60 * 1000L)
        prefs.setUnifiedJwt(jwt, expiry)
        Log.i(TAG, "Unified JWT obtained, expires in ~${(expiry - System.currentTimeMillis()) / 1000}s")
        return jwt
    }

    private fun jwtExpiryMillis(jwt: String): Long {
        return try {
            val parts = jwt.split(".")
            if (parts.size < 2) return 0L
            val normalized = parts[1].replace('-', '+').replace('_', '/')
            val padded = normalized.padEnd(normalized.length + (4 - normalized.length % 4) % 4, '=')
            val json = String(Base64.decode(padded, Base64.DEFAULT))
            val exp = gson.fromJson(json, Map::class.java)["exp"]
            ((exp as? Number)?.toLong() ?: 0L) * 1000L
        } catch (_: Exception) { 0L }
    }
}
