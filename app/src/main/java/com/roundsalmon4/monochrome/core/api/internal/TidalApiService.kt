package com.roundsalmon4.monochrome.core.api.internal

import com.roundsalmon4.monochrome.core.api.internal.dto.*
import retrofit2.http.GET
import retrofit2.http.Query

interface TidalApiService {

    @GET("search/")
    suspend fun searchTracks(
        @Query("s") query: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 25
    ): ApiResponse<SearchData>

    @GET("search/")
    suspend fun searchArtists(
        @Query("a") query: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 25
    ): ApiResponse<SearchData>

    @GET("search/")
    suspend fun searchAlbums(
        @Query("al") query: String,
        @Query("offset") offset: Int = 0,
        @Query("limit") limit: Int = 25
    ): ApiResponse<SearchData>

    @GET("album/")
    suspend fun getAlbum(@Query("id") albumId: String): ApiResponse<AlbumResponseData>

    @GET("album/")
    suspend fun getAlbumTracks(
        @Query("id") albumId: String,
        @Query("offset") offset: Int,
        @Query("limit") limit: Int = 500
    ): ApiResponse<AlbumResponseData>

    @GET("artist/")
    suspend fun getArtist(@Query("id") artistId: String): com.google.gson.JsonObject

    @GET("artist/")
    suspend fun getArtistAlbums(
        @Query("f") artistId: String,
        @Query("skip_tracks") skipTracks: Boolean = true
    ): ArtistAlbumsResponse

    @GET("track/")
    suspend fun getTrack(@Query("id") trackId: String): ApiResponse<TrackResponseData>

    @GET("playlist/")
    suspend fun getPlaylist(@Query("id") playlistId: String): ApiResponse<SearchData>

    @GET("mix/")
    suspend fun getMix(@Query("id") mixId: String): ApiResponse<SearchData>
}
