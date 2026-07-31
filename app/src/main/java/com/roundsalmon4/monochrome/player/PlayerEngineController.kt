package com.roundsalmon4.monochrome.player

import android.content.Context
import android.net.Uri
import androidx.media3.common.AudioAttributes
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.common.MediaMetadata
import androidx.media3.common.PlaybackParameters
import androidx.media3.common.Player
import androidx.media3.exoplayer.DefaultLoadControl
import androidx.media3.exoplayer.ExoPlayer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class PlayerEngineController(context: Context) {

    companion object {
        private const val POSITION_TICK_INTERVAL_MS = 500L
    }

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)

    private val loadControl = DefaultLoadControl.Builder()
        .setBufferDurationsMs(15_000, 60_000, 2_500, 5_000)
        .build()

    val exoPlayer: ExoPlayer = ExoPlayer.Builder(context)
        .setLoadControl(loadControl)
        .setAudioAttributes(
            AudioAttributes.Builder()
                .setContentType(C.AUDIO_CONTENT_TYPE_MUSIC)
                .setUsage(C.USAGE_MEDIA)
                .build(),
            true
        )
        .setHandleAudioBecomingNoisy(true)
        .build()

    private val _playbackState = MutableStateFlow(PlayerPlaybackSnapshot())
    val playbackState: StateFlow<PlayerPlaybackSnapshot> = _playbackState.asStateFlow()

    private val _trackEnded = MutableSharedFlow<Unit>(extraBufferCapacity = 1)
    val trackEnded: SharedFlow<Unit> = _trackEnded.asSharedFlow()

    private var prevPlaybackState = Player.STATE_IDLE

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { updateSnapshot() }
        override fun onPlaybackStateChanged(state: Int) {
            if (prevPlaybackState != Player.STATE_ENDED && state == Player.STATE_ENDED) {
                _trackEnded.tryEmit(Unit)
            }
            prevPlaybackState = state
            updateSnapshot()
        }
        override fun onPlaybackParametersChanged(playbackParameters: PlaybackParameters) { updateSnapshot() }
    }

    init {
        exoPlayer.addListener(playerListener)
        startPositionTicker()
    }

    private fun startPositionTicker() {
        scope.launch {
            while (isActive) {
                val state = exoPlayer.playbackState
                if (state == Player.STATE_READY || state == Player.STATE_BUFFERING) {
                    updateSnapshot()
                }
                delay(POSITION_TICK_INTERVAL_MS)
            }
        }
    }

    fun play(
        url: String,
        mimeType: String? = null,
        startPositionMs: Long = 0L,
        title: String = "",
        artist: String = "",
        album: String = "",
        artworkUrl: String = ""
    ) {
        val builder = MediaItem.Builder().setUri(url)
        mimeType?.let { builder.setMimeType(it) }
        val metadataBuilder = MediaMetadata.Builder()
        if (title.isNotBlank()) metadataBuilder.setTitle(title)
        if (artist.isNotBlank()) metadataBuilder.setArtist(artist)
        if (album.isNotBlank()) metadataBuilder.setAlbumTitle(album)
        if (artworkUrl.isNotBlank()) {
            runCatching { metadataBuilder.setArtworkUri(Uri.parse(artworkUrl)) }
        }
        builder.setMediaMetadata(metadataBuilder.build())
        exoPlayer.setMediaItem(builder.build())
        exoPlayer.prepare()
        if (startPositionMs > 0) {
            exoPlayer.seekTo(startPositionMs.coerceAtLeast(0))
        }
        exoPlayer.playWhenReady = true
    }

    fun togglePlayPause() {
        if (exoPlayer.isPlaying) exoPlayer.pause() else exoPlayer.play()
    }

    fun stop() {
        exoPlayer.stop()
        exoPlayer.clearMediaItems()
    }

    fun seekForward(seconds: Long = 30L) = seekBy(seconds * 1000)

    fun seekBackward(seconds: Long = 10L) = seekBy(-seconds * 1000)

    fun seekTo(positionMs: Long) {
        exoPlayer.seekTo(positionMs.coerceAtLeast(0))
    }

    fun seekBy(offsetMs: Long) {
        val newPos = (exoPlayer.currentPosition + offsetMs).coerceIn(0, exoPlayer.duration.coerceAtLeast(0))
        exoPlayer.seekTo(newPos)
    }

    fun setPlaybackSpeed(speed: Float) {
        exoPlayer.playbackParameters = PlaybackParameters(speed)
    }

    fun release() {
        exoPlayer.removeListener(playerListener)
        scope.cancel()
        exoPlayer.release()
    }

    private fun updateSnapshot() {
        val newSnapshot = PlayerPlaybackSnapshot(
            isPlaying = exoPlayer.isPlaying,
            currentPosition = exoPlayer.currentPosition.coerceAtLeast(0),
            duration = if (exoPlayer.duration > 0) exoPlayer.duration else 0L,
            bufferedPosition = exoPlayer.bufferedPosition.coerceAtLeast(0),
            playbackSpeed = exoPlayer.playbackParameters.speed,
            isBuffering = exoPlayer.playbackState == Player.STATE_BUFFERING,
            isLive = exoPlayer.isCurrentMediaItemLive,
            playbackState = exoPlayer.playbackState
        )
        if (newSnapshot != _playbackState.value) {
            _playbackState.value = newSnapshot
        }
    }
}
