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
            Regex("""(?:apiClientId|api_client_id)"\s*:\s*"([A-Za-z0-9]{20,50})""""),
            Regex("""client_id\s*[:=]\s*'([A-Za-z0-9]{20,50})'"""),
            Regex("""clientId\s*[:=]\s*'([A-Za-z0-9]{20,50})'"""),
            Regex("""client_id=([A-Za-z0-9]{20,50})"""),
            Regex("""client_id%3D([A-Za-z0-9]{20,50})""")
        )
        private val SCRIPT_SRC_PATTERN = Regex("""(?:src|href)="([^"]+\.js)"""")
        private const val MAX_SCRIPTS = 15
        private const val EXTRACT_COOLDOWN_MS = 10_000L
        private const val FETCH_TIMEOUT_MS = 15_000L
        private const val MAX_CANDIDATES = 10
        private const val DESKTOP_UA =
            "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/126.0.0.0 Safari/537.36"
    }

    private val gson = Gson()
    @Volatile private var clientId: String? = null
    @Volatile private var lastExtractAttempt: Long = 0L

    @Volatile
    var wasNotFound: Boolean = false

    data class SoundCloudHealth(val available: Boolean, val detail: String)

    /**
     * Probes the actual playback path: extract a client ID and confirm it with a
     * lightweight search. Used by the Settings backend health section so the
     * SoundCloud indicator reflects whether playback would really work.
     */
    suspend fun checkAvailability(): SoundCloudHealth {
        val id = getValidClientId(force = true)
            ?: return SoundCloudHealth(false, "client ID extraction failed")
        val body = searchTracks("music", id)
        if (body == null) {
            return if (clientId == null) SoundCloudHealth(false, "client ID rejected (401)")
            else SoundCloudHealth(false, "search failed")
        }
        val first = parseCollection(body)?.firstOrNull()
            ?: return SoundCloudHealth(true, "search ok (no test track)")
        val stream = resolveStream(first, id)
            ?: return SoundCloudHealth(false, "search ok, stream resolution failed")
        return SoundCloudHealth(true, "stream ok (${stream.second})")
    }

    internal fun parseCollection(body: String): List<Map<String, Any?>>? = runCatching {
        val root = gson.fromJson<Map<String, Any?>>(body, object : TypeToken<Map<String, Any?>>() {}.type)
        @Suppress("UNCHECKED_CAST")
        root["collection"] as? List<Map<String, Any?>>
    }.getOrNull()

    /** Raw search results for the generic discovery layer. */
    internal suspend fun searchRaw(query: String, limit: Int): List<Map<String, Any?>>? {
        val id = getValidClientId() ?: return null
        val body = searchTracks(query, id) ?: return null
        return parseCollection(body)?.take(limit)
    }

    /** Raw track maps from the trending/top charts for the generic discovery layer. */
    internal suspend fun chartsRaw(limit: Int): List<Map<String, Any?>>? {
        val id = getValidClientId() ?: return null
        val url = "https://api-v2.soundcloud.com/charts?kind=top&genre=soundcloud:genres:all-music&limit=$limit&client_id=$id"
        val body = httpGetText(url) ?: return null
        return runCatching {
            val collection = gson.fromJson(body, Map::class.java)["collection"] as? List<Map<String, Any?>>
            collection?.mapNotNull { element ->
                val track = element["track"] as? Map<String, Any?>
                if (track != null) track else (element["playlist"] as? Map<String, Any?>)?.let { it }
            }
        }.getOrNull()?.take(limit)
    }

    /** Resolves a stream for an arbitrary track map (used by the discovery layer). */
    internal suspend fun resolveFromMap(track: Map<String, Any?>): Pair<String, String>? {
        val id = getValidClientId() ?: return null
        return resolveStream(track, id)
    }

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
        Log.d(TAG, "Searching '$query' (client_id=${id.take(8)}...)")

        val searchBody = searchTracks(query, id)
        if (searchBody == null && clientId == null) {
            // The client ID was rejected (401) and cleared; re-extract once and retry.
            val retryId = getValidClientId(force = true) ?: return null
            Log.w(TAG, "Retrying search with refreshed client_id=${retryId.take(8)}...")
            val retryBody = searchTracks(query, retryId)
            return handleSearchBody(retryBody, title, artist, retryId)
        }
        return handleSearchBody(searchBody, title, artist, id)
    }

    /** Returns the raw search body, or null (and clears [clientId]) on a 401. */
    private suspend fun searchTracks(query: String, id: String): String? {
        val searchUrl = "$SEARCH_URL?q=${java.net.URLEncoder.encode(query, "UTF-8")}&client_id=$id&limit=5"
        return try {
            val req = Request.Builder().url(searchUrl)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code == 401) {
                        Log.w(TAG, "SoundCloud search: 401, rotating client ID")
                        clientId = null
                    } else {
                        Log.w(TAG, "SoundCloud search: HTTP ${resp.code}")
                    }
                    return null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud search failed: ${e.message}")
            null
        }
    }

    private suspend fun handleSearchBody(
        searchBody: String?,
        title: String,
        artist: String,
        id: String
    ): MonochromeStreamResult? {
        if (searchBody == null) return null

        val searchResult = runCatching {
            gson.fromJson<Map<String, Any?>>(
                searchBody, object : TypeToken<Map<String, Any?>>() {}.type
            )
        }.getOrNull() ?: return null

        @Suppress("UNCHECKED_CAST")
        val collection = searchResult["collection"] as? List<Map<String, Any?>> ?: return null
        if (collection.isEmpty()) {
            Log.d(TAG, "SoundCloud: no results for '$title $artist'")
            wasNotFound = true
            return null
        }

        val bestMatch = collection.firstNotNullOfOrNull { item ->
            val trackTitle = item["title"]?.toString() ?: return@firstNotNullOfOrNull null
            if (StringUtil.titlesMatch(title, trackTitle)) item else null
        }
        if (bestMatch == null) {
            Log.d(TAG, "SoundCloud: no title match for '$title' in ${collection.size} results")
            wasNotFound = true
            return null
        }

        val trackId = numericAwareId(bestMatch["id"]) ?: return null
        val trackTitle = bestMatch["title"]?.toString() ?: title
        Log.d(TAG, "SoundCloud: matched track $trackId - $trackTitle")

        val stream = resolveStream(bestMatch, id)
        if (stream != null) {
            Log.d(TAG, "SoundCloud: stream resolved for track $trackId (${stream.second})")
            return MonochromeStreamResult(
                url = stream.first,
                mimeType = stream.second,
                isrc = null,
                title = trackTitle
            )
        }
        Log.w(TAG, "SoundCloud: no stream URL for matched track $trackId")
        return null
    }

    /**
     * Resolves a playable URL from the track's `media.transcodings` (the
     * `/tracks/{id}/streams` endpoint was retired and now returns 404).
     * Prefers a progressive (mp3) transcoding, falling back to HLS.
     */
    internal suspend fun resolveStream(track: Map<String, Any?>, clientId: String): Pair<String, String>? {
        val media = track["media"] as? Map<*, *>
        val transcodings = media?.get("transcodings") as? List<*>
        if (transcodings.isNullOrEmpty()) {
            Log.w(TAG, "SoundCloud: track has no transcodings")
            return null
        }

        data class Transcoding(val url: String, val protocol: String)
        val parsed = transcodings.mapNotNull { item ->
            val m = item as? Map<*, *> ?: return@mapNotNull null
            val url = m["url"]?.toString() ?: return@mapNotNull null
            val protocol = (m["format"] as? Map<*, *>)?.get("protocol")?.toString().orEmpty()
            Transcoding(url, protocol)
        }
        val chosen = parsed.firstOrNull { it.protocol == "progressive" }
            ?: parsed.firstOrNull { it.protocol == "hls" }
            ?: parsed.firstOrNull()
            ?: run {
                Log.w(TAG, "SoundCloud: no usable transcoding")
                return null
            }
        Log.d(TAG, "SoundCloud: using ${chosen.protocol.ifBlank { "unknown" }} transcoding")

        val auth = track["track_authorization"]?.toString()
        val url = buildString {
            append(chosen.url)
            append("?client_id=").append(clientId)
            if (!auth.isNullOrBlank()) {
                append("&track_authorization=").append(java.net.URLEncoder.encode(auth, "UTF-8"))
            }
        }
        val body = httpGetText(url) ?: return null
        val streamUrl = runCatching {
            gson.fromJson(body, Map::class.java)["url"]?.toString()
        }.getOrNull()
        if (streamUrl.isNullOrBlank()) {
            Log.w(TAG, "SoundCloud: transcoding response had no url")
            return null
        }
        val mime = if (chosen.protocol == "hls") "application/x-mpegURL" else "audio/mpeg"
        return streamUrl to mime
    }

    internal suspend fun httpGetText(url: String): String? {
        return try {
            val req = Request.Builder().url(url)
                .header("User-Agent", "Mozilla/5.0 (Linux; Android 14) AppleWebKit/537.36 ChromePlayer/0.1")
                .build()
            withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }.use { resp ->
                if (!resp.isSuccessful) {
                    Log.w(TAG, "SoundCloud request HTTP ${resp.code}")
                    null
                } else resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "SoundCloud request failed: ${e.message}")
            null
        }
    }

    internal suspend fun getValidClientId(force: Boolean = false): String? {
        if (!force) {
            clientId?.let { return it }
        } else {
            clientId = null
        }

        val now = System.currentTimeMillis()
        if (!force && now - lastExtractAttempt < EXTRACT_COOLDOWN_MS) {
            Log.d(TAG, "Extraction cooldown active")
            return null
        }

        val extracted = tryExtractClientId()
        lastExtractAttempt = now
        if (extracted != null) {
            clientId = extracted
            Log.d(TAG, "SoundCloud: extracted client ID ${extracted.take(8)}...")
            return extracted
        }
        Log.w(TAG, "SoundCloud: client ID extraction failed; SoundCloud unavailable")
        return null
    }

    /**
     * Fetch the web app and locate a client_id. On modern SoundCloud the ID is
     * no longer in the HTML; it lives in one of the JS bundles referenced by the
     * page, so fall back to crawling the script assets.
     */
    private suspend fun tryExtractClientId(): String? {
        val html = fetchText(WEB_URL) ?: return null
        extractClientIdFromHtml(html)?.let { return it }

        val scripts = SCRIPT_SRC_PATTERN.findAll(html)
            .map { it.groupValues[1] }
            .map { absUrl(it) }
            .filter { it != null }
            .map { it!! }
            .distinct()
            .take(MAX_SCRIPTS)
            .toList()
        Log.d(TAG, "SoundCloud: no inline client_id, scanning ${scripts.size} script bundle(s)")

        for (script in scripts) {
            val js = fetchText(script) ?: continue
            extractClientIdFromHtml(js)?.let { return it }
        }
        return null
    }

    private suspend fun fetchText(url: String): String? {
        return kotlinx.coroutines.withTimeoutOrNull(FETCH_TIMEOUT_MS) {
            try {
                val req = Request.Builder().url(url)
                    .header("User-Agent", DESKTOP_UA)
                    .header("Accept", "text/html,application/javascript,*/*")
                    .build()
                withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }.use { resp ->
                    if (!resp.isSuccessful) {
                        Log.w(TAG, "SoundCloud fetch HTTP ${resp.code} for $url")
                        null
                    } else resp.body?.string()
                }
            } catch (e: Exception) {
                Log.w(TAG, "SoundCloud fetch failed for $url: ${e.message}")
                null
            }
        }
    }

    private fun absUrl(src: String): String? = when {
        src.startsWith("https://") -> src
        src.startsWith("//") -> "https:$src"
        src.startsWith("http://") -> src
        src.startsWith("/") -> "https://soundcloud.com$src"
        else -> null
    }

    /** Gson parses JSON numbers as Double, which stringifies large IDs in scientific notation. */
    internal fun numericAwareId(raw: Any?): String? = when (raw) {
        is Number -> raw.toLong().toString()
        else -> raw?.toString()?.takeIf { it.isNotBlank() }
    }

    private fun extractClientIdFromHtml(text: String): String? {
        val candidates = mutableListOf<String>()
        for (pattern in CLIENT_ID_PATTERNS) {
            pattern.findAll(text).forEach { match ->
                val id = match.groupValues[1]
                if (id.length in 20..50 && candidates.size < MAX_CANDIDATES) {
                    candidates.add(id)
                }
            }
        }
        return candidates.firstOrNull()
    }
}

