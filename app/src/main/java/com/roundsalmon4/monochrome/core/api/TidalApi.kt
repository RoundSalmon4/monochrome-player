package com.roundsalmon4.monochrome.core.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.roundsalmon4.monochrome.core.api.internal.MonochromePlaybackClient
import com.roundsalmon4.monochrome.core.api.internal.MonochromeSessionRefresher
import com.roundsalmon4.monochrome.core.api.internal.TidalApiService
import com.roundsalmon4.monochrome.core.api.internal.TracksApiService
import com.roundsalmon4.monochrome.core.api.internal.UnifiedPlaybackClient
import com.roundsalmon4.monochrome.core.discovery.DiscoveredItem
import com.roundsalmon4.monochrome.core.discovery.TracksApiSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.async
import kotlinx.coroutines.cancel
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import com.roundsalmon4.monochrome.core.api.internal.AmazonMusicClient
import com.roundsalmon4.monochrome.core.api.internal.SoundCloudClient
import com.roundsalmon4.monochrome.core.api.internal.DeezerProxyClient
import com.roundsalmon4.monochrome.core.api.internal.InternetArchiveClient
import com.roundsalmon4.monochrome.core.api.internal.JioSaavnClient
import com.roundsalmon4.monochrome.core.api.internal.QobuzProxyClient
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumItem
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumResponseData
import com.roundsalmon4.monochrome.core.api.internal.dto.ApiResponse
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistItem
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistResponseData
import com.roundsalmon4.monochrome.core.api.internal.dto.SearchData
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
    private val internetArchiveClient: InternetArchiveClient,
    private val jioSaavnClient: JioSaavnClient,
    private val tracksApiSource: TracksApiSource,
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

    /** New official metadata API (tracks.monochrome.st) — primary source; classic instances are the fallback. */
    private val tracksApi: TracksApiService = Retrofit.Builder()
        .baseUrl("https://tracks.monochrome.st/")
        .client(okHttpClient)
        .addConverterFactory(GsonConverterFactory.create(GsonBuilder().setLenient().create()))
        .build()
        .create(TracksApiService::class.java)

    private sealed class Res<out R> {
        class Ok<R>(val value: R) : Res<R>()
        object AllFailed : Res<Nothing>()
    }

    private suspend fun <T> tryInstances(block: suspend (TidalApiService) -> T): T {
        val channel = Channel<Res<T>>(Channel.UNLIMITED)
        val failures = java.util.concurrent.atomic.AtomicInteger(0)
        val lastError = java.util.concurrent.atomic.AtomicReference<Throwable?>(null)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        services.forEach { service ->
            scope.launch {
                try {
                    channel.send(Res.Ok(withTimeout(INSTANCE_TIMEOUT_MS) { block(service) }))
                } catch (e: Exception) {
                    android.util.Log.w("ChromePlayer", "API instance failed: ${e.message}")
                    lastError.set(e)
                    if (failures.incrementAndGet() == services.size) channel.send(Res.AllFailed)
                }
            }
        }
        when (val res = channel.receive()) {
            is Res.Ok<T> -> {
                scope.cancel()
                return res.value
            }
            Res.AllFailed -> {
                scope.cancel()
                throw lastError.get() ?: RuntimeException("All API instances failed")
            }
        }
    }

    private companion object {
        private const val INSTANCE_TIMEOUT_MS = 6_000L
    }

    private fun logResolved(source: String, chainStart: Long, url: String, mimeType: String) {
        android.util.Log.i(
            "ChromePlayer-TidalApi",
            "Resolved via $source in ${System.currentTimeMillis() - chainStart}ms -> $url ($mimeType)"
        )
    }

    // ---------------------------------------------------------------- tracks.monochrome.st
    // Primary metadata source: Monochrome's new official Music API. Every method
    // falls back to the classic instance pool (samidy etc.) when it fails, so a
    // single outage never breaks browsing.

    /** Cancellation-safe wrapper: new API first, classic instance pool on failure. */
    private suspend fun <T> newApiTry(name: String, block: suspend () -> T, fallback: suspend () -> T): T = try {
        block()
    } catch (e: kotlinx.coroutines.CancellationException) {
        throw e
    } catch (e: Exception) {
        android.util.Log.w("ChromePlayer-TidalApi", "tracks API $name failed (${e.message}); using classic instances")
        fallback()
    }

    private fun JsonObject.mapArray(key: String): List<Map<String, Any?>> {
        val arr = getAsJsonArray(key) ?: return emptyList()
        val gson = Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        return arr.map { gson.fromJson<Map<String, Any?>>(it, type) }
    }

    private fun gsonMap(obj: JsonObject): Map<String, Any?> {
        val gson = Gson()
        val type = object : com.google.gson.reflect.TypeToken<Map<String, Any?>>() {}.type
        return gson.fromJson<Map<String, Any?>>(obj.toString(), type)
    }

    private fun str(v: Any?): String? = v?.toString()?.takeIf { it.isNotBlank() }
    private fun num(v: Any?): Long = when (v) { is Number -> v.toLong(); else -> 0L }

    private fun trackFromTracksApi(m: Map<String, Any?>): Track? {
        val id = str(m["trackId"] ?: m["id"]) ?: return null
        val title = str(m["title"]) ?: return null
        val artistName = (m["artistNames"] as? List<*>)?.firstOrNull()?.toString().orEmpty()
            .ifBlank { "Unknown Artist" }
        val artistId = (m["artistIds"] as? List<*>)?.firstOrNull()?.toString().orEmpty()
        return Track(
            id = id, title = title, artistName = artistName, artistId = artistId,
            albumId = str(m["releaseId"]).orEmpty(), albumTitle = "",
            coverUrl = str(m["artwork"]).orEmpty(),
            durationMs = num(m["duration"]),
            trackNumber = num(m["trackNumber"]).toInt(),
            isrc = str(m["isrc"]).orEmpty()
        )
    }

    private fun trackFromRelease(m: Map<String, Any?>, albumTitle: String, albumId: String): Track? {
        val id = str(m["trackId"] ?: m["id"]) ?: return null
        val title = str(m["title"]) ?: return null
        @Suppress("UNCHECKED_CAST")
        val artists = m["artists"] as? List<Map<String, Any?>>
        val artistName = artists?.firstOrNull()?.let { str(it["name"]) ?: str(it["displayName"]) }
            .orEmpty().ifBlank { "Unknown Artist" }
        val artistId = artists?.firstOrNull()?.let { str(it["artistId"]) }.orEmpty()
        return Track(
            id = id, title = title, artistName = artistName, artistId = artistId,
            albumId = albumId, albumTitle = albumTitle,
            coverUrl = str(m["artwork"]).orEmpty(),
            durationMs = num(m["duration"]),
            trackNumber = num(m["trackNumber"]).toInt(),
            isrc = str(m["isrc"]).orEmpty()
        )
    }

    private fun albumFromRelease(m: Map<String, Any?>): Album {
        val id = str(m["releaseId"] ?: m["id"]).orEmpty()
        @Suppress("UNCHECKED_CAST")
        val artists = m["artists"] as? List<Map<String, Any?>>
        val artistNames = m["artistNames"] as? List<*>
        val artistName = artists?.firstOrNull()?.let { str(it["name"]) }.orEmpty().ifBlank {
            artistNames?.firstOrNull()?.toString().orEmpty().ifBlank { "Unknown Artist" }
        }
        val artistId = artists?.firstOrNull()?.let { str(it["artistId"]) }
            ?: (m["artistIds"] as? List<*>)?.firstOrNull()?.toString().orEmpty()
        return Album(
            id = id,
            title = str(m["title"]).orEmpty(),
            artistName = artistName,
            artistId = artistId,
            coverUrl = str(m["artwork"]).orEmpty(),
            year = str(m["releaseDate"])?.take(4)?.toIntOrNull() ?: 0,
            trackCount = num(m["trackCount"]).toInt(),
            durationMs = 0L
        )
    }

    private fun artistFromTracksApi(m: Map<String, Any?>): Artist = Artist(
        id = str(m["artistId"] ?: m["id"]).orEmpty(),
        name = str(m["name"]) ?: str(m["displayName"]) ?: "Unknown",
        imageUrl = str(m["avatar"]).orEmpty(),
        albumCount = 0
    )

    private suspend fun newApiTracks(query: String, limit: Int, offset: Int): List<Track> =
        tracksApi.searchTracks(query, limit, offset).mapArray("tracks").mapNotNull { trackFromTracksApi(it) }

    private suspend fun newApiReleases(query: String, limit: Int, offset: Int): List<Album> =
        tracksApi.searchReleases(query, limit, offset).mapArray("releases").map { albumFromRelease(it) }

    private suspend fun newApiArtists(query: String, limit: Int, offset: Int): List<Artist> =
        tracksApi.searchArtists(query, limit, offset).mapArray("artists").map { artistFromTracksApi(it) }

    private suspend fun newApiGetAlbum(albumId: String): Pair<Album, List<Track>> {
        val root = tracksApi.getRelease(albumId)
        val album = albumFromRelease(gsonMap(root))
        val tracks = root.mapArray("tracks").mapNotNull { trackFromRelease(it, album.title, album.id) }
        android.util.Log.i("ChromePlayer-TidalApi", "tracks API getAlbum($albumId) -> '${album.title}' with ${tracks.size} track(s)")
        return Pair(album, tracks)
    }

    private suspend fun newApiGetArtist(artistId: String): Artist =
        artistFromTracksApi(gsonMap(tracksApi.getArtist(artistId)))

    private suspend fun newApiArtistAlbums(artistId: String): List<Album> {
        val root = tracksApi.getArtist(artistId)
        val releases = root.mapArray("releases").map { albumFromRelease(it) }
        val albums = root.mapArray("albums").map { albumFromRelease(it) }
        val combined = (releases + albums).distinctBy { it.id }
        android.util.Log.i("ChromePlayer-TidalApi", "tracks API artistAlbums($artistId) -> ${combined.size} release(s)")
        return combined
    }

    suspend fun search(query: String): SearchResults = newApiTry("search", {
        SearchResults(
            tracks = newApiTracks(query, 25, 0),
            artists = newApiArtists(query, 25, 0),
            albums = newApiReleases(query, 25, 0)
        )
    }, {
        coroutineScope {
            val tracksDef = async { trackResults { it.searchTracks(query) } }
            val artistsDef = async { tryInstances { it.searchArtists(query) }.data?.artists?.items.orEmpty().map { it.toArtist() } }
            val albumsDef = async { tryInstances { it.searchAlbums(query) }.data?.albums?.items.orEmpty().map { it.toAlbum() } }
            SearchResults(tracks = tracksDef.await(), artists = artistsDef.await(), albums = albumsDef.await())
        }
    })

    suspend fun searchAlbums(query: String): List<Album> = newApiTry("searchAlbums",
        { newApiReleases(query, 25, 0) },
        { tryInstances { it.searchAlbums(query) }.data?.albums?.items.orEmpty().map { it.toAlbum() } }
    )

    suspend fun searchTracks(query: String, offset: Int): List<Track> = newApiTry("searchTracks",
        { newApiTracks(query, 25, offset) },
        { trackResults { it.searchTracks(query, offset) } }
    )

    suspend fun searchArtists(query: String, offset: Int): List<Artist> = newApiTry("searchArtists",
        { newApiArtists(query, 25, offset) },
        { tryInstances { it.searchArtists(query, offset) }.data?.artists?.items.orEmpty().map { it.toArtist() } }
    )

    suspend fun searchAlbums(query: String, offset: Int): List<Album> = newApiTry("searchAlbums",
        { newApiReleases(query, 25, offset) },
        { tryInstances { it.searchAlbums(query, offset) }.data?.albums?.items.orEmpty().map { it.toAlbum() } }
    )

    suspend fun searchArtists(query: String): List<Artist> = newApiTry("searchArtists",
        { newApiArtists(query, 25, 0) },
        { tryInstances { it.searchArtists(query) }.data?.artists?.items.orEmpty().map { it.toArtist() } }
    )

    /** Some instances (e.g. monochrome-api.samidy.com) return track search as a flat `data.items` array instead of `data.tracks.items`. */
    private suspend fun trackResults(block: suspend (TidalApiService) -> ApiResponse<SearchData>): List<Track> {
        val data = tryInstances(block).data
        val nested = data?.tracks?.items
        if (!nested.isNullOrEmpty()) return nested.map { it.toTrack() }
        return data?.items.orEmpty().map { it.toTrack() }
    }

    suspend fun getAlbum(albumId: String): Pair<Album, List<Track>> {
        try {
            return newApiGetAlbum(albumId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChromePlayer-TidalApi", "tracks API getAlbum failed (${e.message}); using classic instances")
        }
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
        try {
            return newApiGetArtist(artistId)
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            android.util.Log.w("ChromePlayer-TidalApi", "tracks API getArtist failed (${e.message}); using classic instances")
        }
        val json = tryInstances { it.getArtist(artistId) }
        // Handle both {"version":"2.x","data":{"id":...,"name":...}} and
        // {"version":"2.x","artist":{"id":...,"name":...},"cover":{...}} formats
        val data = json.get("data")?.asJsonObject ?: json.get("artist")?.asJsonObject
            ?: throw RuntimeException("Artist not found")
        val gson = Gson()
        val detail = gson.fromJson(data, ArtistResponseData::class.java)
        return detail.toArtist()
    }

    suspend fun getArtistAlbums(artistId: String): List<Album> = newApiTry("getArtistAlbums",
        { newApiArtistAlbums(artistId) },
        {
            val response = tryInstances { it.getArtistAlbums(artistId) }
            response.albums?.items.orEmpty().map { it.toAlbum() }
        }
    )

    suspend fun getTrackStreamUrl(track: Track): StreamUrl {
        val chainStart = System.currentTimeMillis()
        val maxChainMs = 30_000L
        android.util.Log.i(
            "ChromePlayer-TidalApi",
            "getTrackStreamUrl start: '${track.title}' - ${track.artistName} (id=${track.id}, isrc=${track.isrc})"
        )
        fun elapsed(): Boolean = System.currentTimeMillis() - chainStart > maxChainMs

        fun remaining(): Long = maxOf(1_000L, maxChainMs - (System.currentTimeMillis() - chainStart))

        // 0a. Monochrome Tracks API (tracks.monochrome.st): official lossless FLAC,
        // direct. Only valid for ids minted by the new API (18-digit); classic
        // TIDAL ids (9-10 digits) skip this probe entirely.
        if (track.id.length >= 15) {
            try {
                val resolved = withTimeout(remaining()) {
                    tracksApiSource.resolveStream(
                        DiscoveredItem(id = track.id, title = track.title, artist = track.artistName)
                    )
                }
                if (resolved != null) {
                    logResolved("TracksApi", chainStart, resolved.url, resolved.mimeType)
                    return StreamUrl(url = resolved.url, mimeType = resolved.mimeType)
                }
                android.util.Log.w("ChromePlayer-TidalApi", "Tracks API stream unavailable for id=${track.id}")
            } catch (e: Exception) {
                android.util.Log.w("ChromePlayer-TidalApi", "Tracks API stream failed: ${e.message}")
            }
            if (elapsed()) {
                android.util.Log.w("ChromePlayer", "Chain budget exhausted after TracksApi")
                throw trackNotFound(track, listOf("Monochrome Tracks" to true))
            }
        }

        // 0. Monochrome Playback: in-house lossless source
        monochromeSessionRefresher.startAutoRefresh()
        try {
            monochromeSessionRefresher.getValidToken()
            val result = withTimeout(remaining()) {
                monochromePlaybackClient.getStreamUrl(
                    title = track.title, artist = track.artistName,
                    isrc = track.isrc, durationMs = track.durationMs
                )
            }
            if (result != null) {
                logResolved("Monochrome", chainStart, result.url, result.mimeType)
                return StreamUrl(url = result.url, mimeType = result.mimeType)
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Monochrome Playback failed: ${e.message}") }
        val monoNotFound = monochromePlaybackClient.wasNotFound
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Monochrome"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound)) }
        // 0b. Unified Playback (music-api.geeked.wtf): consolidated Amazon/Monochrome/Qobuz source
        try {
            val result = withTimeout(remaining()) {
                unifiedPlaybackClient.getStreamUrl(
                    title = track.title, artist = track.artistName,
                    isrc = track.isrc, durationMs = track.durationMs
                )
            }
            if (result != null) {
                logResolved("Unified", chainStart, result.url, result.mimeType)
                return StreamUrl(url = result.url, mimeType = result.mimeType)
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Unified Playback failed: ${e.message}") }
        val unifiedNotFound = unifiedPlaybackClient.wasNotFound
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Unified"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound)) }
        // 0c. SoundCloud: free catalog, no ISRC or auth required
        try {
            val result = withTimeout(remaining()) {
                soundCloudClient.getStreamUrl(title = track.title, artist = track.artistName)
            }
            if (result != null) {
                logResolved("SoundCloud", chainStart, result.url, result.mimeType)
                return StreamUrl(url = result.url, mimeType = result.mimeType)
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "SoundCloud failed: ${e.message}") }
        val scNotFound = soundCloudClient.wasNotFound
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after SoundCloud"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound, "SoundCloud" to scNotFound)) }
        // 1. Qobuz: direct FLAC, no DRM
        var qobuzNotFound = false
        var deezerNotFound = false
        if (track.isrc.isNotBlank()) {
            try {
                val url = withTimeout(remaining()) {
                    qobuzProxyClient.getStreamUrl(
                        isrc = track.isrc,
                        title = track.title,
                        artist = track.artistName,
                        album = track.albumTitle,
                        durationMs = track.durationMs
                    )
                }
                if (url != null) {
                    logResolved("Qobuz", chainStart, url, "audio/flac")
                    return StreamUrl(url = url, mimeType = "audio/flac")
                }
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Qobuz failed: ${e.message}") }
            qobuzNotFound = qobuzProxyClient.wasNotFound
            if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Qobuz"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound, "SoundCloud" to scNotFound, "Qobuz" to qobuzNotFound)) }
            // 2. Deezer: backup
            try {
                val url = withTimeout(remaining()) { deezerProxyClient.getStreamUrl(track.isrc) }
                if (url != null) {
                    logResolved("Deezer", chainStart, url, "audio/mp4")
                    return StreamUrl(url = url, mimeType = "audio/mp4")
                }
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Deezer failed: ${e.message}") }
            deezerNotFound = deezerProxyClient.wasNotFound
        }
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Deezer"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound, "SoundCloud" to scNotFound, "Qobuz" to qobuzNotFound, "Deezer" to deezerNotFound)) }
        // 2b. Internet Archive: free lossless (FLAC) backup, no sign-up or auth
        try {
            val result = withTimeout(remaining()) {
                internetArchiveClient.getStreamUrl(title = track.title, artist = track.artistName)
            }
            if (result != null) {
                logResolved("InternetArchive", chainStart, result.url, result.mimeType)
                return StreamUrl(url = result.url, mimeType = result.mimeType)
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Internet Archive failed: ${e.message}") }
        val iaNotFound = internetArchiveClient.wasNotFound
        if (elapsed()) { android.util.Log.w("ChromePlayer", "Chain budget exhausted after Internet Archive"); throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound, "SoundCloud" to scNotFound, "Qobuz" to qobuzNotFound, "Deezer" to deezerNotFound, "Internet Archive" to iaNotFound)) }
        // 2c. JioSaavn: free AAC 320 backup, no sign-up or auth
        try {
            val result = withTimeout(remaining()) {
                jioSaavnClient.getStreamUrl(
                    title = track.title,
                    artist = track.artistName,
                    album = track.albumTitle,
                    durationMs = track.durationMs
                )
            }
            if (result != null) {
                logResolved("JioSaavn", chainStart, result.url, result.mimeType)
                return StreamUrl(url = result.url, mimeType = result.mimeType)
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "JioSaavn failed: ${e.message}") }
        val jioSaavnNotFound = jioSaavnClient.wasNotFound
        // 3. Amazon Music: last resort
        try {
            val url = withTimeout(minOf(12_000L, remaining())) { getAmazonStreamUrl(track.id) }
            if (url != null) {
                logResolved("Amazon", chainStart, url, "audio/mp4")
                return StreamUrl(url = url, mimeType = "audio/mp4")
            }
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Amazon Music failed: ${e.message}") }
        throw trackNotFound(track, listOf("Monochrome" to monoNotFound, "Unified" to unifiedNotFound, "SoundCloud" to scNotFound, "Qobuz" to qobuzNotFound, "Deezer" to deezerNotFound, "Internet Archive" to iaNotFound, "JioSaavn" to jioSaavnNotFound))
    }

    private fun trackNotFound(track: Track, notFoundFlags: List<Pair<String, Boolean>>): RuntimeException {
        val status = notFoundFlags.joinToString { (name, nf) ->
            "$name=${if (nf) "not-found" else "unavailable/down"}"
        }
        android.util.Log.e(
            "ChromePlayer-TidalApi",
            "Track resolution failed: '${track.title}' - ${track.artistName} (id=${track.id}, isrc=${track.isrc}) | $status"
        )
        val sources = notFoundFlags.filter { it.second }.map { it.first }
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
            val url = amazonMusicClient.getStreamUrl(trackId)
            if (url != null) android.util.Log.d("ChromePlayer", "Amazon: got stream URL")
            return url
        } catch (e: Exception) {
            android.util.Log.w("ChromePlayer", "Amazon: failed: ${e.message}")
        }
        return null
    }

    private fun albumCoverUrl(cover: String?): String {
        if (cover.isNullOrBlank()) return ""
        if (cover.startsWith("http")) return cover
        val path = cover.replace("-", "/")
        return "https://resources.tidal.com/images/$path/640x640.jpg"
    }

    private fun artistPictureUrl(picture: String?): String {
        if (picture.isNullOrBlank()) return ""
        if (picture.startsWith("http")) return picture
        val path = picture.replace("-", "/")
        return "https://resources.tidal.com/images/$path/320x320.jpg"
    }
}
