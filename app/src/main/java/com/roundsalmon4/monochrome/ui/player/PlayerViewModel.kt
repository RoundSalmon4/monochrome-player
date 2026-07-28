package com.roundsalmon4.monochrome.ui.player

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.player.PlayerEngineController
import com.roundsalmon4.monochrome.player.PlayerStateManager
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class PlayerUiState(
    val currentTrack: Track? = null,
    val isPlaying: Boolean = false,
    val isLoading: Boolean = false,
    val error: String? = null,
    val queueSize: Int = 0,
    val currentIndex: Int = -1
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    private val tidalApi: TidalApi,
    private val playerController: PlayerEngineController,
    private val playerStateManager: PlayerStateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playerStateManager.queue.collect { queue ->
                val track = queue.currentTrack
                _uiState.value = _uiState.value.copy(
                    currentTrack = track,
                    queueSize = queue.tracks.size,
                    currentIndex = queue.currentIndex
                )
            }
        }
    }

    fun playCurrent() {
        val track = playerStateManager.queue.value.currentTrack ?: return
        playTrack(track)
    }

    fun playTrackAt(index: Int) {
        playerStateManager.setCurrentIndex(index)
        playCurrent()
    }

    private fun playTrack(track: Track) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val streamUrl = tidalApi.getTrackStreamUrl(track.id)
                playerController.play(streamUrl.url, streamUrl.mimeType)
                playerStateManager.updateTrackInfo(track.id, track.title, track.artistName, track.coverUrl)
                playerStateManager.updatePlaybackState(
                    isPlaying = true,
                    currentPosition = 0,
                    duration = track.durationMs
                )
                _uiState.value = _uiState.value.copy(isPlaying = true, isLoading = false)
            } catch (e: Exception) {
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Playback failed")
            }
        }
    }

    fun togglePlayPause() {
        playerController.togglePlayPause()
    }

    fun seekBackward() = playerController.seekBackward()

    fun seekForward() = playerController.seekForward()

    fun nextTrack() {
        playerStateManager.nextTrack()
        playCurrent()
    }

    fun previousTrack() {
        playerStateManager.previousTrack()
        playCurrent()
    }
}
