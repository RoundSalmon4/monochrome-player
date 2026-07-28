package com.roundsalmon4.monochrome.core.api.internal.dto

import com.google.gson.annotations.SerializedName

data class SearchResponse(
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

data class MediaMetadata(
    val tags: List<String>? = null
)

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

data class AlbumResponse(
    val album: AlbumDetail? = null,
    val items: List<AlbumTrackItem>? = null,
    val data: AlbumData? = null
)

data class AlbumData(
    val album: AlbumDetail? = null,
    val items: List<AlbumTrackItem>? = null
)

data class AlbumDetail(
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
    val audioQuality: String? = null,
    val explicit: Boolean? = null,
    val type: String? = null
)

data class AlbumTrackItem(
    val item: TrackItem? = null
)

data class ArtistResponse(
    val artist: ArtistDetail? = null,
    val items: List<AlbumTrackItem>? = null,
    val data: ArtistData? = null
)

data class ArtistData(
    val artist: ArtistDetail? = null,
    val items: List<AlbumTrackItem>? = null
)

data class ArtistDetail(
    val id: String? = null,
    val name: String? = null,
    val picture: String? = null,
    val type: String? = null,
    val albumCount: Int? = null
)

data class TrackResponse(
    val track: TrackDetail? = null,
    val info: PlaybackInfo? = null,
    val originalTrackUrl: String? = null,
    val data: TrackData? = null
)

data class TrackData(
    val track: TrackDetail? = null,
    val info: PlaybackInfo? = null,
    val originalTrackUrl: String? = null
)

data class TrackDetail(
    val id: String? = null,
    val title: String? = null,
    val duration: Int? = null,
    val trackNumber: Int? = null,
    val audioQuality: String? = null,
    val artist: ArtistBrief? = null,
    val album: AlbumBrief? = null,
    val isrc: String? = null
)

data class PlaybackInfo(
    val manifest: String? = null,
    val manifestHash: String? = null,
    val assetPresentation: String? = null,
    val audioQuality: String? = null,
    val audioMode: String? = null,
    val trackId: Int? = null,
    val albumId: Int? = null
)

data class PlaylistResponse(
    val playlist: PlaylistDetail? = null,
    val items: List<AlbumTrackItem>? = null,
    val data: PlaylistData? = null
)

data class PlaylistData(
    val playlist: PlaylistDetail? = null,
    val items: List<AlbumTrackItem>? = null
)

data class PlaylistDetail(
    val uuid: String? = null,
    val title: String? = null,
    val description: String? = null,
    val numberOfTracks: Int? = null,
    val duration: Int? = null,
    val image: String? = null,
    val created: String? = null,
    val type: String? = null
)

data class MixResponse(
    val mix: MixDetail? = null,
    val items: List<AlbumTrackItem>? = null,
    val data: MixData? = null
)

data class MixData(
    val mix: MixDetail? = null,
    val items: List<AlbumTrackItem>? = null
)

data class MixDetail(
    val id: String? = null,
    val title: String? = null,
    val subTitle: String? = null,
    val description: String? = null,
    val mixType: String? = null,
    val images: MixImages? = null
)

data class MixImages(
    val LARGE: MixImage? = null,
    val MEDIUM: MixImage? = null,
    val SMALL: MixImage? = null
)

data class MixImage(
    val url: String? = null,
    val width: Int? = null,
    val height: Int? = null
)
