package com.roundsalmon4.monochrome.ui.library.playlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.database.PlaylistDao
import com.roundsalmon4.monochrome.core.database.entity.PlaylistTrack
import com.roundsalmon4.monochrome.core.api.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlaylistDetailUiState(
    val playlistName: String = "",
    val tracks: List<PlaylistTrack> = emptyList(),
    val trackModels: List<Track> = emptyList(),
    val isLoading: Boolean = true
)

@HiltViewModel
class PlaylistDetailViewModel @Inject constructor(
    private val playlistDao: PlaylistDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlaylistDetailUiState())
    val uiState: StateFlow<PlaylistDetailUiState> = _uiState.asStateFlow()

    fun loadPlaylist(playlistId: Long) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true)
            val playlist = playlistDao.getPlaylistById(playlistId)
            playlistDao.getPlaylistTracks(playlistId).collect { tracks ->
                _uiState.value = PlaylistDetailUiState(
                    playlistName = playlist?.name ?: "Playlist",
                    tracks = tracks,
                    trackModels = tracks.map { pt ->
                        Track(
                            id = pt.trackId, title = pt.title, artistName = pt.artistName,
                            artistId = "", albumId = "", albumTitle = pt.albumTitle,
                            coverUrl = pt.coverUrl, durationMs = pt.durationMs
                        )
                    }
                )
            }
        }
    }

    fun removeTrack(playlistId: Long, trackId: String) {
        viewModelScope.launch {
            playlistDao.removeTrack(playlistId, trackId)
            val count = playlistDao.getTrackCount(playlistId)
            val playlist = playlistDao.getPlaylistById(playlistId)
            if (playlist != null) {
                playlistDao.updatePlaylist(playlist.copy(trackCount = count))
            }
        }
    }
}
