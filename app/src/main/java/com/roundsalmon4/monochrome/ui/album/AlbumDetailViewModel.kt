package com.roundsalmon4.monochrome.ui.album

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.Availability
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.TrackAvailabilityChecker
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
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
    private val tidalApi: TidalApi,
    private val availabilityChecker: TrackAvailabilityChecker
) : ViewModel() {

    private val _uiState = MutableStateFlow(AlbumDetailUiState())
    val uiState: StateFlow<AlbumDetailUiState> = _uiState.asStateFlow()

    val trackStatus: StateFlow<Map<String, Boolean>> = availabilityChecker.trackStatus
    val albumStatus: StateFlow<Map<String, Availability>> = availabilityChecker.albumStatus

    private var loadedId: String? = null
    private var availabilityJob: Job? = null

    fun loadAlbum(albumId: String) {
        if (albumId == loadedId) return
        loadedId = albumId
        viewModelScope.launch {
            _uiState.value = AlbumDetailUiState(isLoading = true)
            try {
                val (album, tracks) = tidalApi.getAlbum(albumId)
                Log.i("ChromePlayer-Availability", "Album $albumId loaded: ${tracks.size} track(s), starting availability checks")
                _uiState.value = AlbumDetailUiState(album = album, tracks = tracks)
                availabilityJob?.cancel()
                availabilityJob = viewModelScope.launch {
                    availabilityChecker.checkAlbum(albumId, tracks)
                }
            } catch (e: Exception) {
                Log.e("ChromePlayer", "Album load failed for id=$albumId", e)
                _uiState.value = AlbumDetailUiState(error = e.message ?: "Failed to load album")
            }
        }
    }
}
