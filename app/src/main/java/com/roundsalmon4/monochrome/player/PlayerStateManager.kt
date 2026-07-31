package com.roundsalmon4.monochrome.player

import com.google.gson.Gson
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
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

enum class RepeatMode { OFF, ALL, ONE }

data class QueueSave(
    val tracks: List<Track>,
    val currentIndex: Int,
    val miniTrackId: String = "",
    val miniTitle: String = "",
    val miniArtist: String = "",
    val miniCover: String = ""
)

@Singleton
class PlayerStateManager @Inject constructor(
    private val prefs: PlayerPreferences
) {

    private val gson = Gson()
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    private val _miniPlayerState = MutableStateFlow(MiniPlayerState())
    val miniPlayerState: StateFlow<MiniPlayerState> = _miniPlayerState.asStateFlow()

    private val _queue = MutableStateFlow(PlaybackQueue())
    val queue: StateFlow<PlaybackQueue> = _queue.asStateFlow()

    private val _isShuffleEnabled = MutableStateFlow(false)
    val isShuffleEnabled: StateFlow<Boolean> = _isShuffleEnabled.asStateFlow()

    private val _repeatMode = MutableStateFlow(RepeatMode.OFF)
    val repeatMode: StateFlow<RepeatMode> = _repeatMode.asStateFlow()

    private var startedTrackId: String? = null
    private var shuffleOrder: MutableList<Int> = mutableListOf()

    init {
        scope.launch {
            val savedJson = runCatching { prefs.getSavedQueueJson() }.getOrNull()
            if (!savedJson.isNullOrBlank()) {
                val saved = runCatching { gson.fromJson(savedJson, QueueSave::class.java) }.getOrNull()
                if (saved != null && saved.tracks.isNotEmpty()) {
                    _queue.value = PlaybackQueue(tracks = saved.tracks, currentIndex = saved.currentIndex)
                    if (saved.miniTrackId.isNotEmpty()) {
                        _miniPlayerState.value = MiniPlayerState(
                            trackId = saved.miniTrackId, title = saved.miniTitle,
                            artistName = saved.miniArtist, coverUrl = saved.miniCover
                        )
                    }
                }
            }
            combine(_queue, _miniPlayerState) { q, m ->
                QueueSave(q.tracks, q.currentIndex, m.trackId, m.title, m.artistName, m.coverUrl)
            }.collect { save ->
                runCatching {
                    prefs.setSavedQueueJson(gson.toJson(save))
                }
            }
        }
    }

    fun setQueue(tracks: List<Track>, startIndex: Int = 0) {
        _queue.value = PlaybackQueue(tracks = tracks, currentIndex = startIndex)
        shuffleOrder.clear()
        _isShuffleEnabled.value = false
    }

    fun setCurrentIndex(index: Int) {
        _queue.value = _queue.value.copy(currentIndex = index)
    }

    /** Advances to the next track honoring shuffle/repeat. Returns false when playback should stop. */
    fun nextTrack(): Boolean {
        val q = _queue.value
        if (q.tracks.isEmpty()) return false
        if (_repeatMode.value == RepeatMode.ONE) return true
        if (_isShuffleEnabled.value) {
            val pos = shuffleOrder.indexOf(q.currentIndex)
            val nextPos = pos + 1
            if (nextPos < shuffleOrder.size) {
                setCurrentIndex(shuffleOrder[nextPos])
                return true
            }
            return if (_repeatMode.value == RepeatMode.ALL) {
                shuffleOrder.firstOrNull()?.let { setCurrentIndex(it); true } ?: false
            } else false
        }
        if (q.hasNext) {
            setCurrentIndex(q.currentIndex + 1)
            return true
        }
        return if (_repeatMode.value == RepeatMode.ALL) {
            setCurrentIndex(0)
            true
        } else false
    }

    fun previousTrack() {
        val q = _queue.value
        if (q.tracks.isEmpty()) return
        if (_isShuffleEnabled.value) {
            val pos = shuffleOrder.indexOf(q.currentIndex)
            val prev = shuffleOrder.getOrNull(pos - 1) ?: return
            setCurrentIndex(prev)
        } else if (q.hasPrevious) {
            setCurrentIndex(q.currentIndex - 1)
        }
    }

    fun toggleShuffle() {
        val q = _queue.value
        if (q.tracks.isEmpty()) return
        _isShuffleEnabled.value = !_isShuffleEnabled.value
        if (_isShuffleEnabled.value) {
            shuffleOrder = q.tracks.indices.shuffled().toMutableList()
            val current = q.currentIndex
            if (current >= 0) {
                shuffleOrder.remove(current)
                shuffleOrder.add(0, current)
            }
        } else {
            shuffleOrder.clear()
        }
    }

    fun cycleRepeatMode() {
        _repeatMode.value = when (_repeatMode.value) {
            RepeatMode.OFF -> RepeatMode.ALL
            RepeatMode.ALL -> RepeatMode.ONE
            RepeatMode.ONE -> RepeatMode.OFF
        }
    }

    /** Returns true when the given track should start playing (i.e. it differs from the last started track). */
    fun shouldStartPlayback(track: Track): Boolean {
        if (track.id == startedTrackId) return false
        startedTrackId = track.id
        return true
    }

    fun markPlaybackFailed(trackId: String) {
        if (startedTrackId == trackId) startedTrackId = null
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
        startedTrackId = null
        shuffleOrder.clear()
        _isShuffleEnabled.value = false
    }
}
