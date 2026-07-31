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

    private var lastPositionSaveAt = 0L
    private var prevWasPlaying = false

    init {
        viewModelScope.launch {
            playerStateManager.queue.collect { queue ->
                val track = queue.currentTrack
                _uiState.value = _uiState.value.copy(
                    currentTrack = track,
                    queueSize = queue.tracks.size,
                    currentIndex = queue.currentIndex
                )
                if (track != null && playerStateManager.shouldStartPlayback(track)) {
                    playTrack(track)
                }
            }
        }
        viewModelScope.launch {
            playerController.playbackState.collect { snap ->
                val track = _uiState.value.currentTrack
                _uiState.value = _uiState.value.copy(
                    isPlaying = snap.isPlaying,
                    currentPosition = snap.currentPosition,
                    duration = snap.duration,
                    bufferedPosition = snap.bufferedPosition,
                    playbackSpeed = snap.playbackSpeed
                )
                playerStateManager.updatePlaybackState(snap.isPlaying, snap.currentPosition, snap.duration, snap.bufferedPosition)

                if (track != null && snap.isPlaying) {
                    val now = System.currentTimeMillis()
                    if (now - lastPositionSaveAt > POSITION_SAVE_INTERVAL_MS) {
                        lastPositionSaveAt = now
                        persistPosition(track, snap.currentPosition)
                    }
                } else if (track != null && prevWasPlaying && !snap.isPlaying && snap.playbackState == Player.STATE_READY) {
                    persistPosition(track, snap.currentPosition)
                }
                prevWasPlaying = snap.isPlaying

                if (snap.playbackState == Player.STATE_ENDED) {
                    track?.let { persistPosition(it, it.durationMs) }
                    nextTrack()
                }
            }
        }
    }

    private fun playTrack(track: Track) {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val streamUrl = tidalApi.getTrackStreamUrl(track)
                val saved = historyDao.getById(track.id)
                val resumeMs = if (saved != null && saved.positionMs in 1 until (track.durationMs - RESUME_SKIP_END_MS))
                    saved.positionMs else 0L
                playerController.play(
                    url = streamUrl.url, mimeType = streamUrl.mimeType,
                    startPositionMs = resumeMs,
                    title = track.title, artist = track.artistName,
                    album = track.albumTitle, artworkUrl = track.coverUrl
                )
                PlaybackService.start(playerController, context)
                playerStateManager.updateTrackInfo(track.id, track.title, track.artistName, track.coverUrl)
                historyDao.upsert(
                    ListenHistoryEntry(
                        trackId = track.id, title = track.title, artistName = track.artistName,
                        artistId = track.artistId, albumTitle = track.albumTitle,
                        coverUrl = track.coverUrl, durationMs = track.durationMs,
                        positionMs = resumeMs, timestamp = System.currentTimeMillis()
                    )
                )
                _uiState.value = _uiState.value.copy(isPlaying = true, isLoading = false)
            } catch (e: Exception) {
                Log.e("ChromePlayer", "playTrack failed for track=${track.id} ${track.title}", e)
                playerStateManager.markPlaybackFailed(track.id)
                _uiState.value = _uiState.value.copy(isLoading = false, error = e.message ?: "Playback failed")
            }
        }
    }

    private fun persistPosition(track: Track, positionMs: Long) {
        viewModelScope.launch {
            historyDao.upsert(
                ListenHistoryEntry(
                    trackId = track.id, title = track.title, artistName = track.artistName,
                    artistId = track.artistId, albumTitle = track.albumTitle,
                    coverUrl = track.coverUrl, durationMs = track.durationMs,
                    positionMs = positionMs.coerceAtLeast(0L).coerceAtMost(track.durationMs),
                    timestamp = System.currentTimeMillis()
                )
            )
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
    }

    fun previousTrack() {
        playerStateManager.previousTrack()
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 5_000L
        private const val RESUME_SKIP_END_MS = 10_000L
    }
}
