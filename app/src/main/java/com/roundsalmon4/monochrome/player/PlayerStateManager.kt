package com.roundsalmon4.monochrome.player

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

@Singleton
class PlayerStateManager @Inject constructor() {

    private val _miniPlayerState = MutableStateFlow(MiniPlayerState())
    val miniPlayerState: StateFlow<MiniPlayerState> = _miniPlayerState.asStateFlow()

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
    }
}
