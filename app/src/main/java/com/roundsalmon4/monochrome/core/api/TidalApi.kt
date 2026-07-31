package com.roundsalmon4.monochrome.core.api

import com.google.gson.Gson
import com.google.gson.JsonObject
import com.roundsalmon4.monochrome.core.api.internal.MonochromePlaybackClient
import com.roundsalmon4.monochrome.core.api.internal.MonochromeSessionRefresher
import com.roundsalmon4.monochrome.core.api.internal.TidalApiService
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import com.roundsalmon4.monochrome.core.api.internal.AmazonMusicClient
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
        return response.data?.albums?.items.orEmpty().map { it.toAlbum() }
    }

    suspend fun getTrackStreamUrl(track: Track): StreamUrl {
        // 0. Monochrome Playback: in-house lossless source (matches upstream priority)
        monochromeSessionRefresher.startAutoRefresh()
        try {
            monochromeSessionRefresher.getValidToken()
            val result = monochromePlaybackClient.getStreamUrl(
                title = track.title, artist = track.artistName,
                isrc = track.isrc, durationMs = track.durationMs
            )
            if (result != null) return StreamUrl(url = result.url, mimeType = result.mimeType)
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Monochrome Playback failed: ${e.message}") }
        // 1. Qobuz: direct FLAC, no DRM
        if (track.isrc.isNotBlank()) {
            try {
                val url = getQobuzStreamUrl(track.isrc)
                if (url != null) return StreamUrl(url = url, mimeType = "audio/flac")
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Qobuz failed: ${e.message}") }
            // 2. Deezer: backup
            try {
                val url = getDeezerStreamUrl(track.isrc)
                if (url != null) return StreamUrl(url = url, mimeType = "audio/mp4")
            } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Deezer failed: ${e.message}") }
        }
        // 3. Amazon Music: last resort
        try {
            val url = getAmazonStreamUrl(track.id)
            if (url != null) return StreamUrl(url = url, mimeType = "audio/mp4")
        } catch (e: Exception) { android.util.Log.w("ChromePlayer", "Amazon Music failed: ${e.message}") }
        throw RuntimeException("All audio sources failed for track ${track.id}")
    }

    private val streamGson = Gson()

    private suspend fun getQobuzStreamUrl(isrc: String): String? {
        val baseUrl = "https://qobuz.kennyy.com.br"
        android.util.Log.d("ChromePlayer", "Qobuz: searching ISRC=$isrc at $baseUrl")
        val searchUrl = "$baseUrl/api/get-music?q=${java.net.URLEncoder.encode(isrc, "UTF-8")}&offset=0"
        val searchResp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(okhttp3.Request.Builder().url(searchUrl).build()).execute()
        }
        android.util.Log.d("ChromePlayer", "Qobuz search: HTTP ${searchResp.code}")
        if (!searchResp.isSuccessful) {
            android.util.Log.w("ChromePlayer", "Qobuz search failed: ${searchResp.body?.string()}")
            return null
        }
        val searchBody = searchResp.body?.string() ?: return null.also { android.util.Log.w("ChromePlayer", "Qobuz empty body") }
        val searchData = streamGson.fromJson(searchBody, Map::class.java)
        android.util.Log.d("ChromePlayer", "Qobuz search response: ${searchBody.take(200)}")

        val items = (searchData["data"] as? Map<*, *>)
            ?.let { it["tracks"] as? Map<*, *> }
            ?.let { it["items"] as? List<*> }
        if (items == null) { android.util.Log.w("ChromePlayer", "Qobuz: no items in response"); return null }

        val match = items.firstNotNullOfOrNull { item ->
            val m = item as? Map<*, *> ?: return@firstNotNullOfOrNull null
            if (m["isrc"]?.toString()?.lowercase() == isrc.lowercase()) m else null
        }
        if (match == null) {
            android.util.Log.w("ChromePlayer", "Qobuz: no ISRC match for $isrc in ${items.size} items")
            return null
        }

        val qobuzTrackId = match["id"]?.toString()
        if (qobuzTrackId == null) { android.util.Log.w("ChromePlayer", "Qobuz: no track id in match"); return null }
        android.util.Log.d("ChromePlayer", "Qobuz: found track $qobuzTrackId")
        val quality = "27" // HI_RES_LOSSLESS
        val streamResp = withContext(Dispatchers.IO) {
            okHttpClient.newCall(
                okhttp3.Request.Builder().url("$baseUrl/api/download-music?track_id=$qobuzTrackId&quality=$quality").build()
            ).execute()
        }
        android.util.Log.d("ChromePlayer", "Qobuz download: HTTP ${streamResp.code}")
        if (!streamResp.isSuccessful) {
            android.util.Log.w("ChromePlayer", "Qobuz download failed: ${streamResp.body?.string()}")
            return null
        }
        val streamBody = streamResp.body?.string() ?: return null.also { android.util.Log.w("ChromePlayer", "Qobuz empty download body") }
        val streamData = streamGson.fromJson(streamBody, Map::class.java)
        if (streamData["success"] == true) {
            val url = (streamData["data"] as? Map<*, *>)?.get("url")?.toString()
            android.util.Log.d("ChromePlayer", if (url != null) "Qobuz: got stream URL" else "Qobuz: no URL in response")
            return url
        }
        android.util.Log.w("ChromePlayer", "Qobuz: success=false in download response")
        return null
    }

    private fun determineMimeType(url: String, manifest: String): String = when {
        url.contains("dash+xml") || manifest.contains("<MPD") -> "application/dash+xml"
        url.contains("mpegURL") || manifest.contains("#EXTM3U") -> "application/x-mpegURL"
        else -> "audio/flac"
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

    private suspend fun getDeezerStreamUrl(isrc: String): String? {
        val baseUrl = "https://dzr.tabs-vs-spaces.wtf"
        val format = "FLAC"
        val url = "$baseUrl/stream/?isrc=${java.net.URLEncoder.encode(isrc, "UTF-8")}&format=$format"
        android.util.Log.d("ChromePlayer", "Deezer: trying $url")
        try {
            val req = okhttp3.Request.Builder().url(url).head().build()
            val resp = withContext(Dispatchers.IO) { okHttpClient.newCall(req).execute() }
            android.util.Log.d("ChromePlayer", "Deezer: HTTP ${resp.code}")
            if (resp.isSuccessful || resp.code == 405 || resp.code == 501) return url
            android.util.Log.w("ChromePlayer", "Deezer: returned ${resp.code}")
        } catch (e: Exception) {
            android.util.Log.w("ChromePlayer", "Deezer: request failed: ${e.message}")
        }
        return null
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
