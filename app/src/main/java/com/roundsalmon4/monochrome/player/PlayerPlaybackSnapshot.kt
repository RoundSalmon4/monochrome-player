package com.roundsalmon4.monochrome.player

data class PlayerPlaybackSnapshot(
    val isPlaying: Boolean = false,
    val currentPosition: Long = 0L,
    val duration: Long = 0L,
    val bufferedPosition: Long = 0L,
    val playbackSpeed: Float = 1.0f,
    val isBuffering: Boolean = false,
    val isLive: Boolean = false,
    val currentQualityLabel: String = "",
    val selectedAudioTrackIndex: Int = -1
)
