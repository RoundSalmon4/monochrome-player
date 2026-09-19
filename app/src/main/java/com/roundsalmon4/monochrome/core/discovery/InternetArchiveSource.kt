package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.roundsalmon4.monochrome.core.api.internal.InternetArchiveClient
import javax.inject.Inject
import javax.inject.Singleton

/** Internet Archive as a native discovery source (search + direct FLAC/MP3 streams). */
@Singleton
class InternetArchiveSource @Inject constructor(
    private val client: InternetArchiveClient
) : DiscoverySource {

    companion object {
        private const val TAG = "ChromePlayer-Discovery-IA"
        private const val AVAILABILITY_TTL_MS = 120_000L
    }

    override val id: String = "archive"
    override val displayName: String = "Internet Archive"

    @Volatile
    private var cachedAvailable: Boolean? = null
    @Volatile
    private var cachedAt: Long = 0L

    override suspend fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        cachedAvailable?.let { if (now - cachedAt < AVAILABILITY_TTL_MS) return it }
        val available = try {
            client.searchItems("music", 1).let { true }
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
        val items = client.searchItems(query, limit).map { item ->
            DiscoveredItem(
                id = item.id,
                title = item.title,
                artist = item.creator.ifBlank { "Unknown" },
                albumTitle = null,
                artworkUrl = item.coverUrl
            )
        }
        Log.i(TAG, "search '$query' -> ${items.size} item(s)")
        return items
    }

    override suspend fun resolveStream(item: DiscoveredItem): ResolvedStream? {
        val resolved = client.resolveItem(item.id) ?: return null
        return ResolvedStream(url = resolved.first, mimeType = resolved.second)
    }
}