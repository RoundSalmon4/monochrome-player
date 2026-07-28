package com.roundsalmon4.monochrome.player

import com.roundsalmon4.monochrome.core.api.model.Track
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import javax.inject.Inject
import javax.inject.Singleton

data class MiniPlayerState(
    val trackId: String = "",
    val title: String = "",
    val artistName: String = "",
    val coverUrl: String = "",
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L
) {
    val hasActivePlayback: Boolean get() = trackId.isNotEmpty()
}

data class PlaybackQueue(
    val tracks: List<Track> = emptyList(),
    val currentIndex: Int = -1
) {
    val currentTrack: Track? get() = tracks.getOrNull(currentIndex)
    val hasNext: Boolean get() = currentIndex + 1 < tracks.size
    val hasPrevious: Boolean get() = currentIndex > 0
}

@Singleton
class PlayerStateManager @Inject constructor() {

    private val _miniPlayerState = MutableStateFlow(MiniPlayerState())
    val miniPlayerState: StateFlow<MiniPlayerState> = _miniPlayerState.asStateFlow()

    private val _queue = MutableStateFlow(PlaybackQueue())
    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queue.value = PlaybackQueue(tracks = tracks, currentIndex = startIndex)
    }

    fun setCurrentIndex(index: Int) {
        _queue.value = _queue.value.copy(currentIndex = index)
    }

    fun nextTrack() {
        val q = _queue.value
        if (q.hasNext) setCurrentIndex(q.currentIndex + 1)
    }

    fun previousTrack() {
        val q = _queue.value
        if (q.hasPrevious) setCurrentIndex(q.currentIndex - 1)
    }

    fun updateTrackInfo(trackId: String, title: String, artistName: String, coverUrl: String) {
        _miniPlayerState.value = _miniPlayerState.value.copy(
            trackId = trackId, title = title, artistName = artistName, coverUrl = coverUrl
        )
    }

    fun updatePlaybackState(isPlaying: Boolean, currentPosition: Long, duration: Long, bufferedPosition: Long = 0L) {
        _miniPlayerState.value = _miniPlayerState.value.copy(
            isPlaying = isPlaying, currentPosition = currentPosition, duration = duration, bufferedPosition = bufferedPosition
        )
    }

    fun clear() {
        _miniPlayerState.value = MiniPlayerState()
        _queue.value = PlaybackQueue()
    }
}
