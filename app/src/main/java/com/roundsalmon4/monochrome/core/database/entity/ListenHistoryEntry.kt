package com.roundsalmon4.monochrome.core.database.entity

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "listen_history")
data class ListenHistoryEntry(
    @PrimaryKey val trackId: String,
    val title: String,
    val artistName: String,
    val artistId: String,
    val albumTitle: String,
    val coverUrl: String,
    val durationMs: Long,
    val positionMs: Long,
    val timestamp: Long
)
