package com.roundsalmon4.monochrome.ui.player

import android.content.Context
import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import androidx.media3.common.Player
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.database.HistoryDao
import com.roundsalmon4.monochrome.core.database.entity.ListenHistoryEntry
import com.roundsalmon4.monochrome.player.PlayerEngineController
import com.roundsalmon4.monochrome.player.PlayerStateManager
import com.roundsalmon4.monochrome.player.service.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
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
    val currentIndex: Int = -1,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playbackSpeed: Float = 1.0f
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tidalApi: TidalApi,
    private val historyDao: HistoryDao,
    private val playerController: PlayerEngineController,
    private val playerStateManager: PlayerStateManager
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    init {
        viewModelScope.launch {
            playerStateManager.queue.collect { queue ->
                _uiState.value = _uiState.value.copy(
                    currentTrack = queue.currentTrack,
                    queueSize = queue.tracks.size,
                    currentIndex = queue.currentIndex
                )
            }
        }
        viewModelScope.launch {
            playerController.playbackState.collect { snap ->
                _uiState.value = _uiState.value.copy(
                    isPlaying = snap.isPlaying,
                    currentPosition = snap.currentPosition,
                    duration = snap.duration,
                    bufferedPosition = snap.bufferedPosition,
                    playbackSpeed = snap.playbackSpeed
                )
                playerStateManager.updatePlaybackState(snap.isPlaying, snap.currentPosition, snap.duration, snap.bufferedPosition)
                if (snap.playbackState == Player.STATE_ENDED) {
                    nextTrack()
                }
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
                val streamUrl = tidalApi.getTrackStreamUrl(track.id, track.isrc)
                playerController.play(streamUrl.url, streamUrl.mimeType)
                PlaybackService.start(playerController, context)
                playerStateManager.updateTrackInfo(track.id, track.title, track.artistName, track.coverUrl)
                historyDao.upsert(
                    ListenHistoryEntry(
                        trackId = track.id, title = track.title, artistName = track.artistName,
                        artistId = track.artistId, albumTitle = track.albumTitle,
                        coverUrl = track.coverUrl, durationMs = track.durationMs,
                        positionMs = 0L, timestamp = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(isPlaying = true, isLoading = false)
            } catch (e: Exception) {
                Log.e("ChromePlayer", "playTrack failed for track=${track.id} ${track.title}", e)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Playback failed")
            }
        }
    }

    fun togglePlayPause() {
        playerController.togglePlayPause()
    }

    fun seekBackward() = playerController.seekBackward()

    fun seekForward() = playerController.seekForward()

    fun seekTo(positionMs: Long) = playerController.seekTo(positionMs)

    fun setPlaybackSpeed(speed: Float) {
        playerController.setPlaybackSpeed(speed)
    }

    fun nextTrack() {
        playerStateManager.nextTrack()
        playCurrent()
    }

    fun previousTrack() {
        playerStateManager.previousTrack()
        playCurrent()
    }
}
