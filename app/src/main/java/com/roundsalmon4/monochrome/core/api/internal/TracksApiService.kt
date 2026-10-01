package com.roundsalmon4.monochrome.core.api.internal

import com.google.gson.JsonObject
import retrofit2.http.GET
import retrofit2.http.Path
import retrofit2.http.Query

/**
 * Client for Monochrome's new official Music API (tracks.monochrome.st, Rust
 * port) — the replacement for the retired *.monochrome.tf instances.
 * Endpoints verified live: /search/{tracks,releases,artists} (q/limit/offset),
 * /releases/{id} (includes full track list), /artists/{id} (embeds releases +
 * top tracks), /track/{id} (streams raw FLAC).
 */
interface TracksApiService {
    @GET("search/tracks")
    suspend fun searchTracks(
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("offset") offset: Int = 0
    ): JsonObject

    @GET("search/releases")
    suspend fun searchReleases(
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("offset") offset: Int = 0
    ): JsonObject

    @GET("search/artists")
    suspend fun searchArtists(
        @Query("q") query: String,
        @Query("limit") limit: Int = 25,
        @Query("offset") offset: Int = 0
    ): JsonObject

    @GET("releases/{id}")
    suspend fun getRelease(@Path("id") releaseId: String): JsonObject

    @GET("artists/{id}")
    suspend fun getArtist(@Path("id") artistId: String): JsonObject
}