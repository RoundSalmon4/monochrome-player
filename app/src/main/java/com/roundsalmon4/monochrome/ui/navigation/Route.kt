package com.roundsalmon4.monochrome.ui.navigation

import kotlinx.serialization.Serializable

@Serializable
sealed interface Route {
    @Serializable data object Home : Route
    @Serializable data object Player : Route
    @Serializable data object Search : Route
    @Serializable data class Album(val albumId: String) : Route
    @Serializable data class Artist(val artistId: String) : Route
    @Serializable data object Library : Route
    @Serializable data object Settings : Route
    @Serializable data object Credits : Route
    @Serializable data class PlaylistDetail(val playlistId: Long) : Route

    /** A browseable, source-native collection (SoundCloud artist or set). */
    @Serializable data class SourceCollection(
        val sourceId: String,
        val itemId: String,
        val kind: String,
        val title: String,
        val artist: String = "",
        val artworkUrl: String = ""
    ) : Route
}
