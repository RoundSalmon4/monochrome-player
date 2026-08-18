package com.roundsalmon4.monochrome.core.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.roundsalmon4.monochrome.core.api.internal.MonochromePlaybackClient
import com.roundsalmon4.monochrome.core.api.internal.MonochromeSessionRefresher
import com.roundsalmon4.monochrome.core.api.internal.TidalApiService
import com.roundsalmon4.monochrome.core.api.internal.UnifiedPlaybackClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.roundsalmon4.monochrome.core.api.internal.AmazonMusicClient
import com.roundsalmon4.monochrome.core.api.internal.SoundCloudClient
import com.roundsalmon4.monochrome.core.api.internal.DeezerProxyClient
import com.roundsalmon4.monochrome.core.api.internal.QobuzProxyClient
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumItem
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumResponseData
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistItem
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistResponseData
import com.roundsalmon4.monochrome.core.api.internal.dto.TrackItem
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.SearchResults
import com.roundsalmon4.monochrome.core.api.model.StreamUrl
import com.roundsalmon4.monochrome.core.api.model.Track
import com.google.gson.GsonBuilder
import okhttp3.OkHttpClient
import retrofit2.Retrofit
import retrofit2.converter.gson.GsonConverterFactory
import javax.inject.Inject
import javax.inject.Named
import javax.inject.Singleton

@Singleton
class TidalApi @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val amazonMusicClient: AmazonMusicClient,
    private val monochromePlaybackClient: MonochromePlaybackClient,
    private val monochromeSessionRefresher: MonochromeSessionRefresher,
    private val unifiedPlaybackClient: UnifiedPlaybackClient,
    private val soundCloudClient: SoundCloudClient,
    private val qobuzProxyClient: QobuzProxyClient,
    private val deezerProxyClient: DeezerProxyClient,
    @Named("api.instances") private val baseUrls: List<String>
) {
    private val services: List<TidalApiService> = baseUrls.map { url ->
        Retrofit.Builder()
            .baseUrl(url)
            .client(okHttpClient)
            .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
            .build()
            .create(TidalApiService::class.java)
    }

    private suspend fun <T> tryInstances(block: suspend (TidalApiService) -> T): T {
        val errors = mutableListOf<Throwable>()
        for (service in services) {
            try {
                return block(service)
            } catch (e: Exception) {
                android.util.Log.w("ChromePlayer", "API instance failed: ${e.message}")
                errors.add(e)
            }
        }
        throw errors.last()
    }

    suspend fun search(query: String): SearchResults {
        val tracks = tryInstances { it.searchTracks(query) }.data?.tracks?.items.orEmpty().map { it.toTrack() }
        val artists = tryInstances { it.searchArtists(query) }.data?.artists?.items.orEmpty().map { it.toArtist() }
        val albums = tryInstances { it.searchAlbums(query) }.data?.albums?.items.orEmpty().map { it.toAlbum() }
        return SearchResults(tracks = tracks, artists = artists, albums = albums)
    }

    suspend fun searchAlbums(query: String): List<Album> {
        return tryInstances { it.searchAlbums(query) }.data?.albums?.items.orEmpty().map { it.toAlbum() }
    }

    suspend fun searchTracks(query: String, offset: Int): List<Track> {
        return tryInstances { it.searchTracks(query, offset) }.data?.tracks?.items.orEmpty().map { it.toTrack() }
    }

    suspend fun searchArtists(query: String, offset: Int): List<Artist> {
        return tryInstances { it.searchArtists(query, offset) }.data?.artists?.items.orEmpty().map { it.toArtist() }
    }

    suspend fun searchAlbums(query: String, offset: Int): List<Album> {
        return tryInstances { it.searchAlbums(query, offset) }.data?.albums?.items.orEmpty().map { it.toAlbum() }
    }

    suspend fun searchArtists(query: String): List<Artist> {
        return tryInstances { it.searchArtists(query) }.data?.artists?.items.orEmpty().map { it.toArtist() }
    }

    suspend fun getAlbum(albumId: String): Pair<Album, List<Track>> {
        val response = tryInstances { it.getAlbum(albumId) }
        val d = response.data ?: throw RuntimeException("Album not found")
        val album = d.toAlbum()
        var tracks = extractTracks(d.items)

        val totalExpected = d.numberOfTracks ?: tracks.size
        if (totalExpected > tracks.size) {
            var offset = tracks.size
            val seen = tracks.map { it.id }.toSet()
            while (tracks.size < totalExpected && tracks.size < 10000) {
                try {
                    val next = tryInstances { it.getAlbumTracks(albumId, offset) }
                    val newTracks = extractTracks(next.data?.items)
                    if (newTracks.isEmpty() || newTracks.first().id in seen) break
                    tracks = tracks + newTracks
                    offset += newTracks.size
                } catch (_: Exception) { break }
            }
        }
        return Pair(album, tracks)
    }

    suspend fun getArtist(artistId: String): Artist {
        val json = tryInstances { it.getArtist(artistId) }
        // Handle both {"version":"2.x","data":{"id":...,"name":...}} and
        // {"version":"2.x","artist":{"id":...,"name":...},"cover":{...}} formats
        val data = json.get("data")?.asJsonObject ?: json.get("artist")?.asJsonObject
            ?: throw RuntimeException("Artist not found")
        val gson = Gson()
        val detail = gson.fromJson(data, ArtistResponseData::class.java)
        return detail.toArtist()
    }

    suspend fun getArtistAlbums(artistId: String): List<Album> {
        val response = tryInstances { it.getArtistAlbums(artistId) }
        return response.albums?.items.orEmpty().map { it.toAlbum() }
    }

    suspend fun getTrackStreamUrl(track: Track): StreamUrl {
        val chainStart = System.currentTimeMillis()
        val maxChainMs = 30_000L
        fun elapsed(): Boolean = System.currentTimeMillis() - chainStart > maxChainMs

        fun remaining(): Long = maxOf(1_000L, maxChainMs - (System.currentTimeMillis() - chainStart))

        // 0. Monochrome Playback: in-house lossless source
        monochromeSessionRefresher.startAutoRefresh()
        try {
            monochromeSessionRefresher.getValidToken()
            val result = monochromePlaybackClient.getStreamUrl(
                title = track.title, artist = track.artistName,
                isrc = track.isrc, durationMs = track.durationMs
            )
            if (result != null) return StreamUrl(url = result.url, mimeType = result.mimeType)
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Monochrome Playback failed: ${e.message}") }
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Monochrome"); throw trackNotFound(track) }
        // 0b. Unified Playback (music-api.geeked.wtf): consolidated Amazon/Monochrome/Qobuz source
        try {
            val result = withTimeout(remaining()) {
                unifiedPlaybackClient.getStreamUrl(
                    title = track.title, artist = track.artistName,
                    isrc = track.isrc, durationMs = track.durationMs
                )
            }
            if (result != null) return StreamUrl(url = result.url, mimeType = result.mimeType)
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Unified Playback failed: ${e.message}") }
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Unified"); throw trackNotFound(track) }
        // 0c. SoundCloud: free catalog, no ISRC or auth required
        try {
            val result = withTimeout(remaining()) {
                soundCloudClient.getStreamUrl(title = track.title, artist = track.artistName)
            }
            if (result != null) return StreamUrl(url = result.url, mimeType = result.mimeType)
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "SoundCloud failed: ${e.message}") }
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after SoundCloud"); throw trackNotFound(track) }
        // 1. Qobuz: direct FLAC, no DRM
        if (track.isrc.isNotBlank()) {
            try {
                val url = withTimeout(remaining()) { qobuzProxyClient.getStreamUrl(track.isrc) }
                if (url != null) return StreamUrl(url = url, mimeType = "audio/flac")
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Qobuz failed: ${e.message}") }
            if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Qobuz"); throw trackNotFound(track) }
            // 2. Deezer: backup
            try {
                val url = withTimeout(remaining()) { deezerProxyClient.getStreamUrl(track.isrc) }
                if (url != null) return StreamUrl(url = url, mimeType = "audio/mp4")
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Deezer failed: ${e.message}") }
        }
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Deezer"); throw trackNotFound(track) }
        // 3. Amazon Music: last resort
        try {
            val url = withTimeout(minOf(12_000L, remaining())) { getAmazonStreamUrl(track.id) }
            if (url != null) return StreamUrl(url = url, mimeType = "audio/mp4")
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Amazon Music failed: ${e.message}") }
        throw trackNotFound(track)
    }

    private fun trackNotFound(track: Track): RuntimeException {
        val sources = mutableListOf<String>()
        if (monochromePlaybackClient.wasNotFound) sources.add("Monochrome")
        if (unifiedPlaybackClient.wasNotFound) sources.add("Unified")
        if (soundCloudClient.wasNotFound) sources.add("SoundCloud")
        if (qobuzProxyClient.wasNotFound) sources.add("Qobuz")
        if (deezerProxyClient.wasNotFound) sources.add("Deezer")
        val msg = if (sources.isNotEmpty()) {
            "Track unavailable on ${sources.joinToString(", ")} and all fallbacks exhausted: ${track.title} - ${track.artistName}"
        } else {
            "All audio sources failed for ${track.title} - ${track.artistName} (ISRC: ${track.isrc})"
        }
        return RuntimeException(msg)
    }

    private fun TrackItem.toTrack() = Track(
        id = id ?: "", title = title ?: "Unknown Track",
        artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
        artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
        albumId = album?.id ?: "", albumTitle = album?.title ?: "Unknown Album",
        coverUrl = albumCoverUrl(album?.cover),
        durationMs = (duration ?: 0) * 1000L, trackNumber = trackNumber ?: 0,
        isrc = isrc ?: ""
    )

    private fun AlbumItem.toAlbum() = Album(
        id = id ?: "", title = title ?: "Unknown Album",
        artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
        artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
        coverUrl = albumCoverUrl(cover),
        year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
        trackCount = numberOfTracks ?: 0, durationMs = (duration ?: 0) * 1000L
    )

    private fun AlbumResponseData.toAlbum() = Album(
        id = id ?: "", title = title ?: "Unknown Album",
        artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
        artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
        coverUrl = albumCoverUrl(cover),
        year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
        trackCount = numberOfTracks ?: 0, durationMs = (duration ?: 0) * 1000L
    )

    private fun ArtistItem.toArtist() = Artist(
        id = id ?: "", name = name ?: "Unknown Artist",
        imageUrl = artistPictureUrl(picture), albumCount = albumCount ?: 0
    )

    private fun ArtistResponseData.toArtist() = Artist(
        id = id ?: "", name = name ?: "Unknown Artist",
        imageUrl = artistPictureUrl(picture), albumCount = albumCount ?: 0
    )

    @Suppress("UNCHECKED_CAST")
    private fun extractTracks(items: List<*>?): List<Track> {
        if (items == null) return emptyList()
        return items.mapNotNull { element ->
            val map = element as? Map<String, Any?> ?: return@mapNotNull null
            val item = (map["item"] as? Map<String, Any?>) ?: map
            fun id(v: Any?): String = when (v) {
                is Number -> v.toLong().toString()
                else -> v?.toString() ?: ""
            }
            val artistMap = item["artist"] as? Map<String, Any?>
            val albumMap = item["album"] as? Map<String, Any?>
            Track(
                id = id(item["id"]),
                title = item["title"]?.toString() ?: "Unknown Track",
                artistName = artistMap?.get("name")?.toString() ?: "Unknown Artist",
                artistId = id(artistMap?.get("id")),
                albumId = id(albumMap?.get("id")),
                albumTitle = albumMap?.get("title")?.toString() ?: "Unknown Album",
                coverUrl = albumCoverUrl(albumMap?.get("cover")?.toString()),
                durationMs = ((item["duration"] as? Number)?.toLong() ?: 0L) * 1000L,
                trackNumber = (item["trackNumber"] as? Number)?.toInt() ?: 0,
                isrc = item["isrc"]?.toString() ?: ""
            )
        }
    }

    private suspend fun getAmazonStreamUrl(trackId: String): String? {
        android.util.Log.d("ChromePlayer", "Amazon: trying track $trackId via Turnstile auth")
        try {
            val result = amazonMusicClient.getStreamUrl(trackId)
            if (result != null) android.util.Log.d("ChromePlayer", "Amazon: got stream URL")
            return result?.url
        } catch (e: Exception) {
            android.util.Log.w("ChromePlayer", "Amazon: failed: ${e.message}")
        }
        return null
    }

    private fun albumCoverUrl(cover: String?): String {
        if (cover.isNullOrBlank()) return ""
        if (cover.startsWith("http")) return cover
        val path = cover.replace("-", "/")
        val result = "https://resources.tidal.com/images/$path/640x640.jpg"
        android.util.Log.v("ChromePlayer", "Cover URL: $result")
        return result
    }

    private fun artistPictureUrl(picture: String?): String {
        if (picture.isNullOrBlank()) return ""
        if (picture.startsWith("http")) return picture
        val path = picture.replace("-", "/")
        return "https://resources.tidal.com/images/$path/320x320.jpg"
    }
}
