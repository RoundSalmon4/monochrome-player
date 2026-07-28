package com.roundsalmon4.monochrome.core.database

import kotlinx.serialization.Serializable

@Serializable
data class ExportData(
    val version: Int = 1,
    val exportedAt: Long = System.currentTimeMillis(),
    val preferences: PreferencesExport? = null,
    val playlists: List<LocalPlaylistExport>? = null,
    val subscriptions: List<LocalSubscriptionExport>? = null
)

@Serializable
data class PreferencesExport(
    val playbackSpeed: Float = 1.0f,
    val defaultQuality: String = "AUTO",
    val resumePlayback: Boolean = true,
    val showMiniPlayer: Boolean = true,
    val themeMode: String = "SYSTEM",
    val useAmoledTheme: Boolean = false,
    val primaryColor: Int = 0xFFFF0000.toInt(),
    val secondaryColor: Int = 0xFF282828.toInt(),
    val colorSchemeMode: String = "STANDARD",
    val pipEnabled: Boolean = true
)

@Serializable
data class LocalPlaylistExport(
    val name: String,
    val createdAt: Long,
    val tracks: List<PlaylistTrackExport>
)

@Serializable
data class PlaylistTrackExport(
    val trackId: String,
    val title: String,
    val artistName: String,
    val coverUrl: String,
    val durationMs: Long,
    val position: Int
)

@Serializable
data class LocalSubscriptionExport(
    val artistId: String,
    val artistName: String,
    val thumbnailUrl: String,
    val subscribedAt: Long
)
