package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.roundsalmon4.monochrome.core.api.internal.JioSaavnClient
import com.roundsalmon4.monochrome.core.api.internal.JioSaavnClient.Song
import javax.inject.Inject
import javax.inject.Singleton

/** JioSaavn as a native discovery source (catalog search + direct AAC 320 streams). */
@Singleton
class JioSaavnSource @Inject constructor(
    private val client: JioSaavnClient
) : DiscoverySource {

    companion object {
        private const val TAG = "ChromePlayer-Discovery-JS"
        private const val AVAILABILITY_TTL_MS = 120_000L
    }

    override val id: String = "jiosaavn"
    override val displayName: String = "JioSaavn"

    @Volatile
    private var cachedAvailable: Boolean? = null
    @Volatile
    private var cachedAt: Long = 0L

    private val sessionItems = java.util.concurrent.ConcurrentHashMap<String, Song>()

    override suspend fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        cachedAvailable?.let { if (now - cachedAt < AVAILABILITY_TTL_MS) return it }
        val available = try {
            client.searchSongs("a", limit = 1)
            true
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
        val songs = client.searchSongs(query, limit)
        return songs.mapNotNull { s ->
            sessionItems[s.id] = s
            DiscoveredItem(
                id = s.id,
                title = s.name,
                artist = s.artists,
                albumTitle = s.album.takeIf { it.isNotBlank() },
                artworkUrl = s.image,
                durationMs = (s.durationSec ?: 0) * 1000L
            )
        }
    }

    override suspend fun resolveStream(item: DiscoveredItem): ResolvedStream? {
        val url = sessionItems[item.id]?.link320 ?: return null
        return ResolvedStream(url = url, mimeType = "audio/mp4")
    }
}