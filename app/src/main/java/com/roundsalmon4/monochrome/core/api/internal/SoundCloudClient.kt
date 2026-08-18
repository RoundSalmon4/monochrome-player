package com.roundsalmon4.monochrome.core.api.internal

import android.util.Log
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.roundsalmon4.monochrome.core.util.StringUtil
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class SoundCloudClient @Inject constructor(
    private val okHttpClient: OkHttpClient
) {
    companion object {
        private const val TAG = "ChromePlayer-SoundCloud"
        private const val SEARCH_URL = "https://api-v2.soundcloud.com/search/tracks"
        private const val WEB_URL = "https://soundcloud.com"
        private val CLIENT_ID_PATTERNS = listOf(
            Regex("""client_id\s*[:=]\s*"([A-Za-z0-9]{20,50})""""),
            Regex("""clientId\s*[:=]\s*"([A-Za-z0-9]{20,50})""""),
            Regex("""client_id\s*[:=]\s*'([A-Za-z0-9]{20,50})'"""),
            Regex("""clientId\s*[:=]\s*'([A-Za-z0-9]{20,50})'""")
        )
        private const val EXTRACT_COOLDOWN_MS = 10_000L
        private const val FETCH_TIMEOUT_MS = 15_000L
        private const val MAX_CANDIDATES = 10
        private val FALLBACK_CLIENT_IDS = listOf(
            "pJ6Fj6roW2KRzWAOwGj6kkQ8VRBJjyBD",
            "6bs1QjDBWrmh7FpcKrIDvzodJ2ZZpRwe",
            "M3trxbPFUFk5jC7dTSqudOxWNLQ4iViz",
            "95f55c0c83c7486f9c8289d67a72386d"
        )
    }

    private val gson = Gson()
    @Volatile private var clientId: String? = null
    @Volatile private var lastExtractAttempt: Long = 0L

    @Volatile
    var wasNotFound: Boolean = false

    suspend fun getStreamUrl(
        title: String,
        artist: String
    ): MonochromeStreamResult? {
        wasNotFound = false
        val id = getValidClientId()
        if (id == null) {
            Log.w(TAG, "No client ID available, cannot search SoundCloud")
            return null
        }

        val query = "$title $artist"
        val searchUrl = "$SEARCH_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&client_id=$id&limit=5"
        val searchBody = try {
            val req = Request.Builder().url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401) {
                        Log.w(TAG, "SoundCloud search: 401, rotating client ID")
                        clientId = null
                    }
                    return null
                }
                resp.body?.string() ?: return null
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud search failed: ${e.message}")
            return null
        }

        val searchResult = runCatching {
            gson.fromJson<Map<String, Any?>>(
                searchBody, object : TypeToken<Map<String, Any?>>() {}.type
            )
        }.getOrNull() ?: return null

        @Suppress("UNCHECKED_CAST")
        val collection = searchResult["collection"] as? List<Map<String, Any?>> ?: return null
        if (collection.isEmpty()) {
            Log.d(TAG, "SoundCloud: no results for '$query'")
            wasNotFound = true
            return null
        }

        val bestMatch = collection.firstNotNullOfOrNull { item ->
            val trackTitle = item["title"]?.toString() ?: return@firstNotNullOfOrNull null
            val trackArtist = (item["user"] as? Map<*, *>)?.get("username")?.toString() ?: ""
            if (StringUtil.titlesMatch(title, trackTitle)) item else null
        }
        if (bestMatch == null) {
            Log.d(TAG, "SoundCloud: no title match for '$title' in ${collection.size} results")
            wasNotFound = true
            return null
        }

        val trackId = bestMatch["id"]?.toString() ?: return null
        val trackTitle = bestMatch["title"]?.toString() ?: title
        Log.d(TAG, "SoundCloud: matched track $trackId - $trackTitle")

        val streamUrl = getStreamForTrack(trackId, id)
        if (streamUrl != null) {
            return MonochromeStreamResult(
                url = streamUrl,
                mimeType = "audio/mpeg",
                isrc = null,
                title = trackTitle
            )
        }
        return null
    }

    private suspend fun getStreamForTrack(trackId: String, clientId: String): String? {
        val streamUrl = "https://api-v2.soundcloud.com/tracks/$trackId/streams?client_id=$clientId"
        return try {
            val req = Request.Builder().url(streamUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SoundCloud streams: HTTP ${resp.code}")
                    return null
                }
                val body = resp.body?.string() ?: return null
                val data = gson.fromJson(body, Map::class.java) ?: return null

                // Try progressive (direct MP3) first
                @Suppress("UNCHECKED_CAST")
                val progressive = data["progressive"] as? List<Map<String, Any?>>
                val direct = progressive?.firstOrNull { it["format"]?.toString()?.contains("mp3") == true }
                val url = direct?.get("url")?.toString()
                if (url != null) return url

                // Fall back to HLS
                val hls = data["hls"] as? Map<*, *>
                hls?.get("url")?.toString()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud streams failed: ${e.message}")
            null
        }
    }

    private suspend fun getValidClientId(): String? {
        clientId?.let { return it }

        val now = System.currentTimeMillis()
        if (now - lastExtractAttempt < EXTRACT_COOLDOWN_MS) {
            return FALLBACK_CLIENT_IDS.firstOrNull()
        }

        val extracted = tryExtractClientId()
        if (extracted != null) {
            clientId = extracted
            lastExtractAttempt = now
            Log.d(TAG, "SoundCloud: extracted client ID from page")
            return extracted
        }

        lastExtractAttempt = now
        return FALLBACK_CLIENT_IDS.firstOrNull()
    }

    private suspend fun tryExtractClientId(): String? {
        return kotlinx.coroutines.withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                val req = Request.Builder().url(WEB_URL)
                    .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                    .build()
                val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
                val html = resp.use { it.body?.string() } ?: return@withTimeoutOrNull null
                extractClientIdFromHtml(html)
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud page fetch failed: ${e.message}")
                null
            }
        }
    }

    private fun extractClientIdFromHtml(html: String): String? {
        val candidates = mutableListOf<String>()
        for (pattern in CLIENT_ID_PATTERNS) {
            pattern.findAll(html).forEach { match ->
                val id = match.groupValues[1]
                if (id.length in 20..50 && candidates.size < MAX_CANDIDATES) {
                    candidates.add(id)
                }
            }
        }
        return candidates.firstOrNull()
    }
}
