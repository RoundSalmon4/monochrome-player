package com.roundsalmon4.monochrome.core.api

import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.SearchResults
import com.roundsalmon4.monochrome.core.api.model.Track
import kotlinx.coroutines.flow.Flow
import javax.inject.Inject
import javax.inject.Singleton

@Singleton
class TidalApi @Inject constructor() {

    fun search(query: String): Flow<SearchResults> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getAlbum(albumId: String): Flow<Album> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getArtist(artistId: String): Flow<Artist> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getTrackStreamUrl(trackId: String): Flow<String> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getHomeFeed(): Flow<List<Album>> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getArtistAlbums(artistId: String): Flow<List<Album>> {
        throw NotImplementedError("Tidal API not yet implemented")
    }

    fun getAlbumTracks(albumId: String): Flow<List<Track>> {
        throw NotImplementedError("Tidal API not yet implemented")
    }
}
