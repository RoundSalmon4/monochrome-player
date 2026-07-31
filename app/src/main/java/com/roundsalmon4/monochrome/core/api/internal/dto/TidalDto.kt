package com.roundsalmon4.monochrome.core.api.internal.dto

data class ApiResponse<T>(
    val version: String? = null,
    val data: T? = null
)

data class ArtistAlbumsResponse(
    val version: String? = null,
    val albums: PaginatedItems<AlbumItem>? = null
)

data class SearchData(
    val tracks: PaginatedItems<TrackItem>? = null,
    val artists: PaginatedItems<ArtistItem>? = null,
    val albums: PaginatedItems<AlbumItem>? = null,
    val playlists: PaginatedItems<PlaylistItem>? = null,
    val videos: PaginatedItems<TrackItem>? = null
)

data class PaginatedItems<T>(
    val items: List<T> = emptyList(),
    val limit: Int = 0,
    val offset: Int = 0,
    val totalNumberOfItems: Int = 0
)

data class AlbumResponseData(
    val id: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val coverColor: String? = null,
    val releaseDate: String? = null,
    val numberOfTracks: Int? = null,
    val numberOfVideos: Int? = null,
    val duration: Int? = null,
    val artist: ArtistBrief? = null,
    val artists: List<ArtistBrief>? = null,
    val items: List<Map<String, Any?>>? = null
)

data class ArtistResponseData(
    val id: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val type: String? = null,
    val albumCount: Int? = null,
    val items: List<Map<String, Any?>>? = null
)

data class TrackResponseData(
    val id: String? = null,
    val title: String? = null,
    val duration: Int? = null,
    val trackNumber: Int? = null,
    val audioQuality: String? = null,
    val artist: ArtistBrief? = null,
    val album: AlbumBrief? = null,
    val manifest: String? = null,
    val manifestHash: String? = null,
    val assetPresentation: String? = null,
    val audioMode: String? = null
)

data class TrackItem(
    val id: String? = null,
    val title: String? = null,
    val duration: Int? = null,
    val trackNumber: Int? = null,
    val volumeNumber: Int? = null,
    val type: String? = null,
    val artist: ArtistBrief? = null,
    val artists: List<ArtistBrief>? = null,
    val album: AlbumBrief? = null,
    val audioQuality: String? = null,
    val isrc: String? = null,
    val copyright: String? = null,
    val streamStartDate: String? = null,
    val popularity: Int? = null,
    val isUnavailable: Boolean? = null
)

data class ArtistBrief(
    val id: String? = null,
    val name: String? = null,
    val type: String? = null,
    val picture: String? = null
)

data class AlbumBrief(
    val id: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val coverColor: String? = null,
    val releaseDate: String? = null,
    val numberOfTracks: Int? = null,
    val numberOfVideos: Int? = null,
    val artist: ArtistBrief? = null,
    val artists: List<ArtistBrief>? = null,
    val audioQuality: String? = null,
    val type: String? = null
)

data class AlbumItem(
    val id: String? = null,
    val title: String? = null,
    val cover: String? = null,
    val coverColor: String? = null,
    val releaseDate: String? = null,
    val numberOfTracks: Int? = null,
    val numberOfVideos: Int? = null,
    val duration: Int? = null,
    val type: String? = null,
    val artist: ArtistBrief? = null,
    val artists: List<ArtistBrief>? = null,
    val audioQuality: String? = null,
    val explicit: Boolean? = null,
    val mediaMetadata: MediaMetadata? = null
)

data class MediaMetadata(val tags: List<String>? = null)

data class ArtistItem(
    val id: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val artistTypes: List<String>? = null,
    val type: String? = null,
    val albumCount: Int? = null,
    val popularTracks: List<TrackItem>? = null
)

data class PlaylistItem(
    val uuid: String? = null,
    val title: String? = null,
    val description: String? = null,
    val numberOfTracks: Int? = null,
    val numberOfVideos: Int? = null,
    val duration: Int? = null,
    val created: String? = null,
    val type: String? = null,
    val image: String? = null,
    val popularTracks: List<TrackItem>? = null
)


