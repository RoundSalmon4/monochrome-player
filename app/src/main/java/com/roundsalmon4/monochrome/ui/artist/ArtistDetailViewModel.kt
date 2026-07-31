package com.roundsalmon4.monochrome.ui.artist

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.database.SubscriptionDao
import com.roundsalmon4.monochrome.core.database.entity.LocalSubscription
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class ArtistDetailUiState(
    val artist: Artist? = null,
    val albums: List<Album> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class ArtistDetailViewModel @Inject constructor(
    private val tidalApi: TidalApi,
    private val subscriptionDao: SubscriptionDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    private val _isSubscribed = MutableStateFlow(false)
    val isSubscribed: StateFlow<Boolean> = _isSubscribed.asStateFlow()

    private var loadedId: String? = null
    private var subscriptionJob: Job? = null

    fun loadArtist(artistId: String) {
        if (artistId == loadedId) return
        loadedId = artistId
        subscriptionJob?.cancel()
        subscriptionJob = viewModelScope.launch {
            subscriptionDao.isSubscribed(artistId).collect { _isSubscribed.value = it }
        }
        viewModelScope.launch {
            _uiState.value = ArtistDetailUiState(isLoading = true)
            try {
                val artist = tidalApi.getArtist(artistId)
                val albums = tidalApi.getArtistAlbums(artistId)
                _uiState.value = ArtistDetailUiState(artist = artist, albums = albums)
            } catch (e: Exception) {
                Log.e("ChromePlayer", "Artist load failed for id=$artistId", e)
                _uiState.value = ArtistDetailUiState(error = e.message ?: "Failed to load artist")
            }
        }
    }

    fun toggleSubscription() {
        val artist = _uiState.value.artist ?: return
        viewModelScope.launch {
            if (_isSubscribed.value) {
                subscriptionDao.unsubscribe(artist.id)
            } else {
                subscriptionDao.subscribe(
                    LocalSubscription(
                        artistId = artist.id, artistName = artist.name,
                        thumbnailUrl = artist.imageUrl, subscribedAt = System.currentTimeMillis()
                    )
                )
            }
        }
    }
}
