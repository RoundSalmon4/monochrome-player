package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.roundsalmon4.monochrome.core.api.internal.SoundCloudClient
import javax.inject.Inject
import javax.inject.Singleton

/** SoundCloud as a native discovery source (trending charts + track search + direct streams). */
@Singleton
class SoundCloudSource @Inject constructor(
    private val client: SoundCloudClient
) : DiscoverySource {

    companion object {
        private const val TAG = "ChromePlayer-Discovery-SC"
        private const val AVAILABILITY_TTL_MS = 120_000L
    }

    override val id: String = "soundcloud"
    override val displayName: String = "SoundCloud"

    @Volatile
    private var cachedAvailable: Boolean? = null
    @Volatile
    private var cachedAt: Long = 0L

    // Keep the raw search/chart items so streams can be resolved later without a re-fetch.
    private val sessionItems = java.util.concurrent.ConcurrentHashMap<String, Map<String, Any?>>()

    override suspend fun isAvailable(): Boolean {
        val now = System.currentTimeMillis()
        cachedAvailable?.let { if (now - cachedAt < AVAILABILITY_TTL_MS) return it }
        val available = client.checkAvailability().available
        cachedAvailable = available
        cachedAt = now
        Log.i(TAG, "available=$available")
        return available
    }

    override suspend fun homeFeed(limit: Int): List<DiscoveredItem> {
        val charts = client.chartsRaw(limit).orEmpty()
        return charts.mapNotNull { mapTrack(it) }
    }

    override suspend fun search(query: String, limit: Int): List<DiscoveredItem> {
        val results = client.searchRaw(query, limit).orEmpty()
        return results.mapNotNull { mapTrack(it) }
    }

    override suspend fun resolveStream(item: DiscoveredItem): ResolvedStream? {
        val raw = sessionItems[item.id] ?: return null
        val resolved = client.resolveFromMap(raw) ?: return null
        return ResolvedStream(url = resolved.first, mimeType = resolved.second)
    }

    private fun mapTrack(raw: Map<String, Any?>): DiscoveredItem? {
        val id = client.numericAwareId(raw["id"]) ?: return null
        val title = raw["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artist = (raw["user"] as? Map<*, *>)?.get("username")?.toString().orEmpty()
        val albumTitle = (raw["album"] as? Map<*, *>)?.get("title")?.toString()
        val durationMs = (raw["duration"] as? Number)?.toLong() ?: 0L
        val artwork = raw["artwork_url"]?.toString()?.replace(Regex("-large\\."), "-t500x500.").orEmpty()
        sessionItems[id] = raw
        return DiscoveredItem(
            id = id,
            title = title,
            artist = artist,
            albumTitle = albumTitle,
            artworkUrl = artwork,
            durationMs = durationMs
        )
    }
}