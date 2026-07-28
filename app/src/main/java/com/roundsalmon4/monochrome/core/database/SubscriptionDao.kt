package com.roundsalmon4.monochrome.core.database

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import com.roundsalmon4.monochrome.core.database.entity.LocalSubscription
import kotlinx.coroutines.flow.Flow

@Dao
interface SubscriptionDao {
    @Query("SELECT * FROM subscriptions ORDER BY subscribedAt DESC")
    fun getAll(): Flow<List<LocalSubscription>>

    @Query("SELECT * FROM subscriptions WHERE artistId = :artistId")
    suspend fun getByArtistId(artistId: String): LocalSubscription?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun subscribe(subscription: LocalSubscription)

    @Query("DELETE FROM subscriptions WHERE artistId = :artistId")
    suspend fun unsubscribe(artistId: String)

    @Query("SELECT EXISTS(SELECT 1 FROM subscriptions WHERE artistId = :artistId)")
    fun isSubscribed(artistId: String): Flow<Boolean>
}
