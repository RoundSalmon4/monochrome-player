package com.roundsalmon4.monochrome.core.database.entity

import androidx.room.Entity
import androidx.room.ForeignKey
import androidx.room.Index

@Entity(
    tableName = "playlist_tracks",
    primaryKeys = ["playlistId", "trackId"],
    foreignKeys = [
        ForeignKey(
            entity = LocalPlaylist::class,
            parentColumns = ["id"],
            childColumns = ["playlistId"],
            onDelete = ForeignKey.CASCADE
        )
    ],
    indices = [Index("playlistId")]
)
data class PlaylistTrack(
    val playlistId: Long,
    val trackId: String,
    val title: String,
    val artistName: String,
    val albumTitle: String,
    val coverUrl: String,
    val durationMs: Long,
    val position: Int
)
