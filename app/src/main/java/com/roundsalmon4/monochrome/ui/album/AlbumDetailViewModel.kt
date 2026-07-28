package com.roundsalmon4.monochrome.ui.album

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class AlbumDetailUiState(
    val album: Album? = null,
    val tracks: List<Track> = emptyList(),
    val isLoading: Boolean = false,
    val error: String? = null
)

@HiltViewModel
class AlbumDetailViewModel @Inject constructor(
    private val tidalApi: TidalApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    private var loadedId: String? = null

    fun loadAlbum(albumId: String) {
        if (albumId == loadedId) return
        loadedId = albumId
        viewModelScope.launch {
            _uiState.value = AlbumDetailUiState(isLoading = true)
            try {
                val (album, tracks) = tidalApi.getAlbum(albumId)
                _uiState.value = AlbumDetailUiState(album = album, tracks = tracks)
            } catch (e: Exception) {
                _uiState.value = AlbumDetailUiState(error = e.message ?: "Failed to load album")
            }
        }
    }
}
