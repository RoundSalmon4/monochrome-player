package com.roundsalmon4.monochrome.ui.artist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import dagger.hilt.android.lifecycle.HiltViewModel
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
    private val tidalApi: TidalApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(ArtistDetailUiState())
    val uiState: StateFlow<ArtistDetailUiState> = _uiState.asStateFlow()

    private var loadedId: String? = null

    fun loadArtist(artistId: String) {
        if (artistId == loadedId) return
        loadedId = artistId
        viewModelScope.launch {
            _uiState.value = ArtistDetailUiState(isLoading = true)
            try {
                val artist = tidalApi.getArtist(artistId)
                val albums = tidalApi.getArtistAlbums(artistId)
                _uiState.value = ArtistDetailUiState(artist = artist, albums = albums)
            } catch (e: Exception) {
                _uiState.value = ArtistDetailUiState(error = e.message ?: "Failed to load artist")
            }
        }
    }
}
