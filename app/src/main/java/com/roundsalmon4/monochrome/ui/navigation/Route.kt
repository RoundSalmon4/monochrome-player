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
    @Serializable data class PlaylistDetail(val playlistId: Long) : Route
}
