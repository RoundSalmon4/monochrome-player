package com.roundsalmon4.monochrome.core.discovery

import android.util.Log
import com.roundsalmon4.monochrome.core.api.internal.SoundCloudClient
import javax.inject.Inject
import javax.inject.Singleton

/** SoundCloud as a native discovery source (trending charts, tracks/sets/artists search, direct streams). */
@Singleton
class SoundCloudSource @Inject constructor(
    private val client: SoundCloudClient
) : DiscoverySource {

    companion object {
        private const val TAG = "ChromePlayer-Discovery-SC"
        private const val AVAILABILITY_TTL_MS = 120_000L
        // Section budget within a single search call.
        private const val TRACK_LIMIT = 6
        private const val SET_LIMIT = 3
        private const val ARTIST_LIMIT = 3
    }

    override val id: String = "soundcloud"
    override val displayName: String = "SoundCloud"

    @Volatile
    private var cachedAvailable: Boolean? = null
    @Volatile
    private var cachedAt: Long = 0L

    // Keep raw maps so streams can be resolved later without a re-fetch.
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
        val items = charts.mapNotNull { toTrackItem(it) }
        Log.i(TAG, "homeFeed: ${items.size} trending item(s)")
        return items
    }

    override suspend fun search(query: String, limit: Int): List<DiscoveredItem> {
        Log.d(TAG, "search '$query' (tracks=$TRACK_LIMIT sets=$SET_LIMIT artists=$ARTIST_LIMIT)")
        val tracks = (client.searchEndpointRaw("tracks", query, TRACK_LIMIT).orEmpty().mapNotNull { toTrackItem(it) })
        val sets = (client.searchEndpointRaw("playlists", query, SET_LIMIT).orEmpty().mapNotNull { toSetItem(it) })
        val artists = (client.searchEndpointRaw("users", query, ARTIST_LIMIT).orEmpty().mapNotNull { toArtistItem(it) })
        val combined = (tracks + sets + artists).take(limit)
        Log.i(TAG, "search '$query' -> ${combined.size} items (${tracks.size} tracks, ${sets.size} sets, ${artists.size} artists)")
        return combined
    }

    override suspend fun resolveStream(item: DiscoveredItem): ResolvedStream? {
        if (item.kind != DiscoveredKind.TRACK) {
            Log.w(TAG, "resolveStream: ${item.kind} items are containers, expand via itemsFor() (${item.title})")
            return null
        }
        val raw = sessionItems[item.id] ?: return null
        val resolved = client.resolveFromMap(raw) ?: return null
        Log.d(TAG, "resolved stream for '${item.title}' (${resolved.second})")
        return ResolvedStream(url = resolved.first, mimeType = resolved.second)
    }

    override suspend fun itemsFor(item: DiscoveredItem): List<DiscoveredItem> {
        Log.d(TAG, "expanding ${item.kind} '${item.title}' (id=${item.id})")
        val raws = when (item.kind) {
            DiscoveredKind.SET -> client.playlistTracksRaw(item.id)
            DiscoveredKind.ARTIST -> client.userTracksRaw(item.id, 25)
            else -> null
        }
        val items = raws.orEmpty().mapNotNull { toTrackItem(it) }
        Log.i(TAG, "expanded ${item.kind} '${item.title}' -> ${items.size} track(s)")
        return items
    }

    // ------------------------------------------------------------------ mappers

    private fun toTrackItem(raw: Map<String, Any?>): DiscoveredItem? {
        val id = client.numericAwareId(raw["id"]) ?: return null
        val title = raw["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artist = (raw["user"] as? Map<*, *>)?.get("username")?.toString().orEmpty()
        val albumTitle = (raw["album"] as? Map<*, *>)?.get("title")?.toString()
        val durationMs = (raw["duration"] as? Number)?.toLong() ?: 0L
        val artwork = raw["artwork_url"]?.toString()?.replace(Regex("-large\\."), "-t500x500.").orEmpty()
        sessionItems[id] = raw
        return DiscoveredItem(id = id, title = title, artist = artist, albumTitle = albumTitle,
            artworkUrl = artwork, durationMs = durationMs, kind = DiscoveredKind.TRACK)
    }

    private fun toSetItem(raw: Map<String, Any?>): DiscoveredItem? {
        val id = client.numericAwareId(raw["id"]) ?: return null
        val title = raw["title"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artist = (raw["user"] as? Map<*, *>)?.get("username")?.toString().orEmpty()
        val artwork = raw["artwork_url"]?.toString()?.replace(Regex("-large\\."), "-t500x500.").orEmpty()
        sessionItems[id] = raw
        return DiscoveredItem(id = id, title = title, artist = artist,
            albumTitle = "Playlist", artworkUrl = artwork, kind = DiscoveredKind.SET)
    }

    private fun toArtistItem(raw: Map<String, Any?>): DiscoveredItem? {
        val id = client.numericAwareId(raw["id"]) ?: return null
        val name = raw["username"]?.toString()?.takeIf { it.isNotBlank() } ?: return null
        val artwork = raw["avatar_url"]?.toString()?.replace(Regex("-large\\."), "-t500x500.").orEmpty()
        sessionItems[id] = raw
        return DiscoveredItem(id = id, title = name, artist = name, artworkUrl = artwork,
            kind = DiscoveredKind.ARTIST)
    }
}