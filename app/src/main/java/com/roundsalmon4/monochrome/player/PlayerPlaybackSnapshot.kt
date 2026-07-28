package com.roundsalmon4.monochrome.player

import androidx.media3.common.Player

data class PlayerPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val playbackState: Int = Player.STATE_IDLE,
    val currentQualityLabel: String = "",
    val selectedAudioTrackIndex: Int = -1
)
