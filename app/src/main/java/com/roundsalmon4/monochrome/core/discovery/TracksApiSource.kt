package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.google.gson.Gson
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Monochrome's new official "Music API Proxy & Track Streamer" (Rust port) at
 * tracks.monochrome.st — the upstream replacement for the retired *.monochrome.tf
 * instances. Verified endpoints: /search/{tracks,releases,artists}?q=,
 * /releases/{id}, /artists/{id}, and /track/{id} which streams raw FLAC directly
 * with no auth, no Turnstile and no decryption.
 */
@Singleton
class TracksApiSource @Inject constructor(
    okHttpClient: OkHttpClient
) : DiscoverySource {

    companion object {
        private const val TAG = "ChromePlayer-Discovery-Tracks"
        private const val BASE = "https://tracks.monochrome.st"
        private const val AVAILABILITY_TTL_MS = 120_000L
    }

    override val id: String = "tracks"
    override val displayName: String = "Monochrome"

    private val client: OkHttpClient = okHttpClient.newBuilder()
        .connectTimeout(6, TimeUnit.SECONDS)
        .readTimeout(6, TimeUnit.SECONDS)
        .callTimeout(8, TimeUnit.SECONDS)
        .build()

    private val gson = Gson()

    @Volatile
    private var cachedAvailable: Boolean? = null
    @Volatile
    private var cachedAt: Long = 0L

    override suspend fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        cachedAvailable?.let { if (now - cachedAt < AVAILABILITY_TTL_MS) return it }
        val available = try {
            get("$BASE/search/tracks?q=test&limit=1") != null
        } catch (e: Exception) {
            Log.w(TAG, "availability probe failed: ${e.message}")
            false
        }
        cachedAvailable = available
        cachedAt = now
        Log.i(TAG, "available=$available")
        return available
    }

    override suspend fun homeFeed(limit: Int): List<DiscoveredItem> = emptyList()

    override suspend fun search(query: String, limit: Int): List<DiscoveredItem> {
        val body = get("$BASE/search/tracks?q=${java.net.URLEncoder.encode(query, "UTF-8")}&limit=${limit.coerceIn(1, 30)}")
            ?: return emptyList()
        val items = runCatching {
            val root = gson.fromJson(body, Map::class.java)
            (root["tracks"] as? List<Map<String, Any?>>).orEmpty().mapNotNull { mapTrack(it) }
        }.getOrElse { e ->
            Log.w(TAG, "search parse failed: ${e.message}")
            emptyList()
        }
        Log.i(TAG, "search '$query' -> ${items.size} track(s)")
        return items
    }

    override suspend fun resolveStream(item: DiscoveredItem): ResolvedStream? {
        val url = "$BASE/track/${item.id}"
        if (!probe(url)) {
            Log.w(TAG, "stream probe failed for ${item.id}")
            return null
        }
        Log.d(TAG, "stream resolved for '${item.title}' (audio/flac)")
        return ResolvedStream(url = url, mimeType = "audio/flac")
    }

    private fun mapTrack(raw: Map<String, Any?>): DiscoveredItem? {
        val id = (raw["trackId"] ?: raw["id"])?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val title = raw["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        @Suppress("UNCHECKED_CAST")
        val artists = (raw["artistNames"] as? List<*>)
            ?.joinToString(", ") { it?.toString().orEmpty() }
            .orEmpty()
        val durationMs = (raw["duration"] as? Number)?.toLong() ?: 0L
        val artwork = raw["artwork"]?.toString().orEmpty()
        return DiscoveredItem(
            id = id,
            title = title,
            artist = artists.ifBlank { "Unknown Artist" },
            artworkUrl = artwork,
            durationMs = durationMs,
            kind = DiscoveredKind.TRACK
        )
    }

    /** Verifies the FLAC stream URL responds before it is handed to the player. */
    private suspend fun probe(url: String): Boolean = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url).header("Range", "bytes=0-4095").get().build()
        try {
            client.newCall(request).execute().use { resp ->
                if (resp.code == 429 || resp.code in 500..599) return@withContext false
                resp.code in 200..299
            }
        } catch (e: Exception) {
            Log.w(TAG, "probe failed: ${e.message}")
            false
        }
    }

    private suspend fun get(url: String): String? = withContext(Dispatchers.IO) {
        val request = Request.Builder().url(url)
            .header("User-Agent", "Mozilla/5.0 ChromePlayer/0.1")
            .build()
        try {
            client.newCall(request).execute().use { resp ->
                if (!resp.isSuccessful) {
                    if (resp.code in 500..599) Log.w(TAG, "HTTP ${resp.code} for $url")
                    return@withContext null
                }
                resp.body?.string()
            }
        } catch (e: Exception) {
            Log.w(TAG, "request failed: ${e.message}")
            null
        }
    }
}