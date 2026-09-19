package com.roundsalmon4.monochrome.core.discovery

/** A catalog item surfaced by a [DiscoverySource]. Playable through that source. */
data class DiscoveredItem(
    val id: String,
    val title: String,
    val artist: String,
    val albumTitle: String? = null,
    val artworkUrl: String = "",
    val durationMs: Long = 0L
)

/** A directly playable stream, already resolved for playback. */
data class ResolvedStream(val url: String, val mimeType: String)

/**
 * A pluggable native-content backend. Each source owns its own catalog
 * (home feed + search) and knows how to resolve any of its items to a direct,
 * playable stream — so the UI only ever surfaces what that source can play.
 *
 * This is deliberately source-agnostic (SoundCloud, JioSaavn, Internet Archive,
 * Qobuz, ...), not tailored to any one backend.
 */
interface DiscoverySource {
    val id: String
    val displayName: String

    /** Cheap probe of whether this backend can serve content right now. */
    suspend fun isAvailable(): Boolean

    /** Rest of the availability check result for the UI (e.g. detailed health). */
    suspend fun availabilityDetail(): String = if (isAvailable()) "available" else "unavailable"

    /** Trending/current items; empty by default when a source has no feed. */
    suspend fun homeFeed(limit: Int): List<DiscoveredItem> = emptyList()

    /** Native catalog search within this source. */
    suspend fun search(query: String, limit: Int): List<DiscoveredItem>

    /** Resolve [item] to a direct stream URL this source can play. */
    suspend fun resolveStream(item: DiscoveredItem): ResolvedStream?
}