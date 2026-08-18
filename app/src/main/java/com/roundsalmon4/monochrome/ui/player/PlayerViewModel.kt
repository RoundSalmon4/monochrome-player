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
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import com.roundsalmon4.monochrome.player.PlayerEngineController
import com.roundsalmon4.monochrome.player.PlayerStateManager
import com.roundsalmon4.monochrome.player.RepeatMode
import com.roundsalmon4.monochrome.player.service.PlaybackService
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    val playbackSpeed: Float = 1.0f,
    val volume: Float = 1.0f,
    val isShuffleEnabled: Boolean = false,
    val repeatMode: RepeatMode = RepeatMode.OFF,
    val sleepTimerMinutes: Int = 0,
    val waveformState: WaveformState = WaveformState()
)

@HiltViewModel
class PlayerViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val tidalApi: TidalApi,
    private val historyDao: HistoryDao,
    private val playerController: PlayerEngineController,
    private val playerStateManager: PlayerStateManager,
    private val playerPreferences: PlayerPreferences,
    private val waveformDecoder: WaveformDecoder
) : ViewModel() {

    private val _uiState = MutableStateFlow(PlayerUiState())
    val uiState: StateFlow<PlayerUiState> = _uiState.asStateFlow()

    private var lastPositionSaveAt = 0L
    private var prevWasPlaying = false
    private var sleepTimerJob: Job? = null

    init {
        viewModelScope.launch {
            val prefs = playerPreferences.uiState.first()
            playerController.setVolume(prefs.volume)
            _uiState.value = _uiState.value.copy(volume = prefs.volume)
        }
        viewModelScope.launch {
            playerStateManager.queue.collect { queue ->
                val track = queue.currentTrack
                _uiState.value = _uiState.value.copy(
                    currentTrack = track,
                    queueSize = queue.tracks.size,
                    currentIndex = queue.currentIndex,
                    waveformState = WaveformState()
                )
                if (track != null && playerStateManager.shouldStartPlayback(track)) {
                    playTrack(track)
                }
            }
        }
        viewModelScope.launch {
            playerStateManager.isShuffleEnabled.collect {
                _uiState.value = _uiState.value.copy(isShuffleEnabled = it)
            }
        }
        viewModelScope.launch {
            playerStateManager.repeatMode.collect {
                _uiState.value = _uiState.value.copy(repeatMode = it)
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
                    playbackSpeed = snap.playbackSpeed,
                    error = snap.error ?: _uiState.value.error
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

                if (snap.isPlaying && !_uiState.value.waveformState.isLoaded && !_uiState.value.waveformState.isError) {
                    val mediaItemUrl = playerController.exoPlayer.currentMediaItem?.localConfiguration?.uri?.toString()
                    if (mediaItemUrl != null && !mediaItemUrl.startsWith("blob:")) {
                        viewModelScope.launch {
                            val samples = waveformDecoder.decode(mediaItemUrl)
                            if (samples != null) {
                                _uiState.value = _uiState.value.copy(
                                    waveformState = WaveformState(samples = samples, isLoaded = true)
                                )
                            } else {
                                _uiState.value = _uiState.value.copy(
                                    waveformState = WaveformState(isError = true)
                                )
                            }
                        }
                    }
                }

                if (snap.playbackState == Player.STATE_ENDED) {
                    track?.let { persistPosition(it, it.durationMs) }
                    if (playerStateManager.repeatMode.value == RepeatMode.ONE) {
                        playerController.replay()
                    } else if (!playerStateManager.nextTrack()) {
                        playerController.stop()
                        PlaybackService.stop(context)
                        playerStateManager.clear()
                    }
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

    fun retry() {
        val track = _uiState.value.currentTrack ?: playerStateManager.queue.value.currentTrack ?: return
        playTrack(track)
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
        if (!playerStateManager.nextTrack()) {
            playerController.stop()
            PlaybackService.stop(context)
            playerStateManager.clear()
        }
    }

    fun previousTrack() {
        if (playerController.exoPlayer.currentPosition > PREV_RESTART_THRESHOLD_MS) {
            playerController.seekTo(0)
        } else {
            playerStateManager.previousTrack()
        }
    }

    fun toggleShuffle() = playerStateManager.toggleShuffle()

    fun cycleRepeatMode() = playerStateManager.cycleRepeatMode()

    fun setVolume(volume: Float) {
        playerController.setVolume(volume)
        _uiState.value = _uiState.value.copy(volume = volume)
        viewModelScope.launch { playerPreferences.setVolume(volume) }
    }

    fun setSleepTimer(minutes: Int) {
        sleepTimerJob?.cancel()
        _uiState.value = _uiState.value.copy(sleepTimerMinutes = minutes)
        if (minutes <= 0) return
        sleepTimerJob = viewModelScope.launch {
            delay(minutes * 60_000L)
            _uiState.value = _uiState.value.copy(sleepTimerMinutes = 0)
            playerController.pause()
            PlaybackService.stop(context)
        }
    }

    companion object {
        private const val POSITION_SAVE_INTERVAL_MS = 2_000L
        private const val RESUME_SKIP_END_MS = 10_000L
        private const val PREV_RESTART_THRESHOLD_MS = 3_000L
    }
}
