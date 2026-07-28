package com.roundsalmon4.monochrome.core.database

import androidx.room.Database
import androidx.room.RoomDatabase
import com.roundsalmon4.monochrome.core.database.entity.ListenHistoryEntry
import com.roundsalmon4.monochrome.core.database.entity.LocalPlaylist
import com.roundsalmon4.monochrome.core.database.entity.LocalSubscription
import com.roundsalmon4.monochrome.core.database.entity.PlaylistTrack

@Database(
    entities = [
        ListenHistoryEntry::class,
        LocalPlaylist::class,
        PlaylistTrack::class,
        LocalSubscription::class
    ],
    version = 1,
    exportSchema = false
)
abstract class AppDatabase : RoomDatabase() {
    abstract fun historyDao(): HistoryDao
    abstract fun playlistDao(): PlaylistDao
    abstract fun subscriptionDao(): SubscriptionDao
}
