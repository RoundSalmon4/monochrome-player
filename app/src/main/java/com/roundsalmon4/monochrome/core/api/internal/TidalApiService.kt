package com.roundsalmon4.monochrome.core.api.internal

import com.roundsalmon4.monochrome.core.api.internal.dto.*
import retrofit2.http.GET
import retrofit2.http.Query

interface TidalApiService {

    @GET("search/")
    suspend fun search(@Query("q") query: String): SearchResponse

    @GET("search/")
    suspend fun searchTracks(@Query("s") query: String): SearchResponse

    @GET("search/")
    suspend fun searchArtists(@Query("a") query: String): SearchResponse

    @GET("search/")
    suspend fun searchAlbums(@Query("al") query: String): SearchResponse

    @GET("search/")
    suspend fun searchPlaylists(@Query("p") query: String): SearchResponse

    @GET("album/")
    suspend fun getAlbum(@Query("id") albumId: String): AlbumResponse

    @GET("album/")
    suspend fun getAlbumTracks(
        @Query("id") albumId: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int = 500
    ): AlbumResponse

    @GET("artist/")
    suspend fun getArtist(@Query("id") artistId: String): ArtistResponse

    @GET("artist/")
    suspend fun getArtistAlbums(
        @Query("f") artistId: String,
        @Query("skip_tracks") skipTracks: Boolean = true
    ): ArtistResponse

    @GET("track/")
    suspend fun getTrack(@Query("id") trackId: String): TrackResponse

    @GET("playlist/")
    suspend fun getPlaylist(@Query("id") playlistId: String): PlaylistResponse

    @GET("playlist/")
    suspend fun getPlaylistTracks(
        @Query("id") playlistId: String,
        @Query("offset") offset: Int
    ): PlaylistResponse

    @GET("mix/")
    suspend fun getMix(@Query("id") mixId: String): MixResponse
}
