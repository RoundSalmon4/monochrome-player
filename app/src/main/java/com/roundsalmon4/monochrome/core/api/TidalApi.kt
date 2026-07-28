package com.roundsalmon4.monochrome.core.api

import com.google.gson.Gson
import com.roundsalmon4.monochrome.core.api.internal.TidalApiService
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumDetail
import com.roundsalmon4.monochrome.core.api.internal.dto.AlbumItem
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistDetail
import com.roundsalmon4.monochrome.core.api.internal.dto.ArtistItem
import com.roundsalmon4.monochrome.core.api.internal.dto.TrackItem
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.SearchResults
import com.roundsalmon4.monochrome.core.api.model.StreamUrl
import com.roundsalmon4.monochrome.core.api.model.Track
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TidalApi @Inject constructor(
    private val service: TidalApiService
) {
    suspend fun search(query: String): SearchResults {
        val response = service.search(query)
        return SearchResults(
            tracks = response.tracks?.items?.map { it.toTrack() } ?: emptyList(),
            artists = response.artists?.items?.map { it.toArtist() } ?: emptyList(),
            albums = response.albums?.items?.map { it.toAlbum() } ?: emptyList()
        )
    }

    suspend fun searchTracks(query: String): List<Track> {
        val response = service.searchTracks(query)
        return response.tracks?.items?.map { it.toTrack() } ?: emptyList()
    }

    suspend fun searchAlbums(query: String): List<Album> {
        val response = service.searchAlbums(query)
        return response.albums?.items?.map { it.toAlbum() } ?: emptyList()
    }

    suspend fun searchArtists(query: String): List<Artist> {
        val response = service.searchArtists(query)
        return response.artists?.items?.map { it.toArtist() } ?: emptyList()
    }

    suspend fun getAlbum(albumId: String): Pair<Album, List<Track>> {
        val response = service.getAlbum(albumId)
        val detail = response.data?.album ?: response.album ?: throw RuntimeException("Album not found")
        val album = detail.toAlbum()
        val rawItems = response.data?.items ?: response.items ?: emptyList()

        var tracks = rawItems.mapNotNull { it.item?.toTrack() }

        val totalExpected = detail.numberOfTracks ?: tracks.size
        if (totalExpected > tracks.size) {
            var offset = tracks.size
            val seen = tracks.map { it.id }.toSet()
            while (tracks.size < totalExpected && tracks.size < 10000) {
                try {
                    val next = service.getAlbumTracks(albumId, offset)
                    val nextItems = next.data?.items ?: next.items ?: break
                    val newTracks = nextItems.mapNotNull { it.item?.toTrack() }
                    if (newTracks.isEmpty()) break
                    if (newTracks.first().id in seen) break
                    tracks = tracks + newTracks
                    offset += newTracks.size
                } catch (_: Exception) { break }
            }
        }
        return Pair(album, tracks)
    }

    suspend fun getArtist(artistId: String): Artist {
        val response = service.getArtist(artistId)
        val detail = response.data?.artist ?: response.artist ?: throw RuntimeException("Artist not found")
        return detail.toArtist()
    }

    suspend fun getArtistAlbums(artistId: String): List<Album> {
        val response = service.getArtistAlbums(artistId)
        val items = response.data?.items ?: response.items ?: emptyList()
        return items.mapNotNull { it.item?.toAlbum() }.distinctBy { it.id }
    }

    suspend fun getTrackStreamUrl(trackId: String): StreamUrl {
        val response = service.getTrack(trackId)
        val playbackInfo = response.data?.info ?: response.info ?: throw RuntimeException("No stream info for track $trackId")

        val manifestStr = playbackInfo.manifest ?: throw RuntimeException("No manifest for track $trackId")
        val decoded = try {
            String(android.util.Base64.decode(manifestStr, android.util.Base64.DEFAULT))
        } catch (_: Exception) { manifestStr }

        val url = if (decoded.contains("<MPD")) {
            "data:application/dash+xml;base64,$manifestStr"
        } else {
            try {
                val gson = Gson()
                val manifestJson = gson.fromJson(decoded, Map::class.java)
                val urls = manifestJson["urls"] as? List<String>
                urls?.firstOrNull() ?: decoded.trim()
            } catch (_: Exception) {
                decoded.trim()
            }
        }

        return StreamUrl(url = url, mimeType = determineMimeType(url, decoded))
    }

    private fun determineMimeType(url: String, manifest: String): String {
        return when {
            url.contains("dash+xml") || manifest.contains("<MPD") -> "application/dash+xml"
            url.contains("mpegURL") || manifest.contains("#EXTM3U") -> "application/x-mpegURL"
            else -> "audio/flac"
        }
    }

    private fun TrackItem.toTrack(): Track {
        return Track(
            id = id ?: "",
            title = title ?: "Unknown Track",
            artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
            artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
            albumId = album?.id ?: "",
            albumTitle = album?.title ?: "Unknown Album",
            coverUrl = albumCoverUrl(album?.cover),
            durationMs = (duration ?: 0) * 1000L,
            trackNumber = trackNumber ?: 0
        )
    }

    private fun AlbumItem.toAlbum(): Album {
        return Album(
            id = id ?: "", title = title ?: "Unknown Album",
            artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
            artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
            coverUrl = albumCoverUrl(cover),
            year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
            trackCount = numberOfTracks ?: 0, durationMs = (duration ?: 0) * 1000L
        )
    }

    private fun TrackItem.toAlbum(): Album {
        return Album(
            id = id ?: "", title = title ?: "Unknown Album",
            artistName = artist?.name ?: artists?.firstOrNull()?.name ?: "Unknown Artist",
            artistId = artist?.id ?: artists?.firstOrNull()?.id ?: "",
            coverUrl = albumCoverUrl(album?.cover),
            year = album?.releaseDate?.take(4)?.toIntOrNull() ?: 0,
            trackCount = 0, durationMs = (duration ?: 0) * 1000L
        )
    }

    private fun AlbumDetail.toAlbum(): Album {
        return Album(
            id = id ?: "", title = title ?: "Unknown Album",
            artistName = artist?.name ?: "Unknown Artist",
            artistId = artist?.id ?: "", coverUrl = albumCoverUrl(cover),
            year = releaseDate?.take(4)?.toIntOrNull() ?: 0,
            trackCount = numberOfTracks ?: 0, durationMs = (duration ?: 0) * 1000L
        )
    }

    private fun ArtistItem.toArtist(): Artist {
        return Artist(id = id ?: "", name = name ?: "Unknown Artist",
            imageUrl = artistPictureUrl(picture), albumCount = albumCount ?: 0)
    }

    private fun ArtistDetail.toArtist(): Artist {
        return Artist(id = id ?: "", name = name ?: "Unknown Artist",
            imageUrl = artistPictureUrl(picture), albumCount = albumCount ?: 0)
    }

    private fun albumCoverUrl(cover: String?): String {
        if (cover.isNullOrBlank()) return ""
        return if (cover.startsWith("http")) cover
        else "https://resources.tidal.com/images/$cover/640x640.jpg"
    }

    private fun artistPictureUrl(picture: String?): String {
        if (picture.isNullOrBlank()) return ""
        return if (picture.startsWith("http")) picture
        else "https://resources.tidal.com/images/$picture/320x320.jpg"
    }
}
