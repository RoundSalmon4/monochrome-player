package com.roundsalmon4.monochrome.core.database.entity

import androidx.room.Entity
import androidx.room.Index
import androidx.room.PrimaryKey

@Entity(
    tableName = "subscriptions",
    indices = [Index("artistId", unique = true)]
)
data class LocalSubscription(
    @PrimaryKey val artistId: String,
    val artistName: String,
    val thumbnailUrl: String,
    val subscribedAt: Long
)
