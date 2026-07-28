package com.roundsalmon4.monochrome.core.api.model

data class SearchResults(
    val tracks: List<Track> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val albums: List<Album> = emptyList()
)

data class Album(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String,
    val coverUrl: String,
    val year: Int = 0,
    val trackCount: Int = 0,
    val durationMs: Long = 0L
)

data class Artist(
    val id: String,
    val name: String,
    val imageUrl: String,
    val albumCount: Int = 0
)

data class Track(
    val id: String,
    val title: String,
    val artistName: String,
    val artistId: String,
    val albumId: String,
    val albumTitle: String,
    val coverUrl: String,
    val durationMs: Long = 0L,
    val trackNumber: Int = 0,
    val isrc: String = ""
)

data class StreamUrl(
    val url: String,
    val mimeType: String
)
