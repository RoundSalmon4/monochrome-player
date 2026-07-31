package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import javax.inject.Inject
import javax.inject.Singleton

data class MonochromeStreamResult(
    val url: String,
    val mimeType: String,
    val isrc: String? = null,
    val title: String? = null
)

@Singleton
class MonochromePlaybackClient @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val prefs: PlayerPreferences
) {
    companion object {
        private const val TAG = "ChromePlayer-Mono"
        private const val API_BASE = "https://track-api.monochrome.tf"
    }

    private val gson = Gson()

    suspend fun getStreamUrl(
        title: String,
        artist: String,
        isrc: String = "",
        durationMs: Long = 0L
    ): MonochromeStreamResult? {
        val (jwt, expiry) = prefs.getMonochromeJwt() ?: run {
            Log.w(TAG, "No Monochrome Playback session stored")
            return null
        }
        if (jwt.isBlank() || System.currentTimeMillis() + 60_000L > expiry) {
            Log.w(TAG, "Monochrome Playback session expired")
            return null
        }

        val body = buildString {
            append("{\"song_name\":").append(gson.toJson(title))
            append(",\"artist\":").append(gson.toJson(artist))
            if (isrc.isNotBlank()) append(",\"isrc\":").append(gson.toJson(isrc))
            if (durationMs > 0) append(",\"duration\":").append(durationMs / 1000L)
            append("}")
        }
        val request = Request.Builder()
            .url("$API_BASE/playback")
            .header("Authorization", "Bearer $jwt")
            .header("Content-Type", "application/json")
            .post(body.toRequestBody("application/json".toMediaType()))
            .build()

        val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(request).execute() }
        if (resp.code == 401) { Log.w(TAG, "Monochrome Playback session rejected"); return null }
        if (resp.code == 429) { Log.w(TAG, "Monochrome Playback rate limited"); return null }
        if (!resp.isSuccessful) { Log.w(TAG, "Monochrome Playback: HTTP ${resp.code}"); return null }

        val raw = resp.body?.string()?.let { runCatching { gson.fromJson<Map<String, Any?>>(it) }.getOrNull() }
            ?: run { Log.w(TAG, "Monochrome Playback: empty/invalid response"); return null }
        val url = raw["url"]?.toString()?.takeIf { it.isNotBlank() } ?: run {
            Log.w(TAG, "Monochrome Playback returned no stream URL")
            return null
        }
        Log.d(TAG, "Got Monochrome Playback stream URL")
        return MonochromeStreamResult(
            url = url,
            mimeType = raw["mime_type"]?.toString() ?: "audio/flac",
            isrc = raw["isrc"]?.toString(),
            title = raw["title"]?.toString()
        )
    }
}
