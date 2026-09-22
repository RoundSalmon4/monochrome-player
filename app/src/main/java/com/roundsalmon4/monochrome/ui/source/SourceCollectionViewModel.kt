package com.roundsalmon4.monochrome.ui.source

import android.util.Log
import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.discovery.DiscoveredItem
import com.roundsalmon4.monochrome.core.discovery.DiscoveredKind
import com.roundsalmon4.monochrome.core.discovery.DiscoverySource
import com.roundsalmon4.monochrome.core.discovery.SourceDiscoveryRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceCollectionUiState(
    val loading: Boolean = false,
    val tracks: List<DiscoveredItem> = emptyList(),
    val error: String? = null
)

/**
 * Shows a source-native collection (a SoundCloud artist or set): resolves the
 * container's tracks (via DiscoverySource.itemsFor) and lets the user pick a
 * track to play. Tapping a collection never plays anything by itself.
 */
@HiltViewModel
class SourceCollectionViewModel @Inject constructor(
    savedStateHandle: SavedStateHandle,
    private val registry: SourceDiscoveryRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "ChromePlayer-SourceCollection"
    }

    private val sourceId: String = checkNotNull(savedStateHandle["sourceId"])
    private val itemId: String = checkNotNull(savedStateHandle["itemId"])
    private val kind: String = checkNotNull(savedStateHandle["kind"])
    val title: String = checkNotNull(savedStateHandle["title"])
    val artist: String = savedStateHandle["artist"] ?: ""
    val artworkUrl: String = savedStateHandle["artworkUrl"] ?: ""

    val item: DiscoveredItem = DiscoveredItem(
        id = itemId,
        title = title,
        artist = artist,
        albumTitle = when (kind) { "SET" -> "Playlist"; else -> null },
        artworkUrl = artworkUrl,
        kind = runCatching { DiscoveredKind.valueOf(kind) }.getOrDefault(DiscoveredKind.TRACK)
    )

    private var source: DiscoverySource? = null

    private val _uiState = MutableStateFlow(SourceCollectionUiState())
    val uiState: StateFlow<SourceCollectionUiState> = _uiState.asStateFlow()

    private val _pendingPlay = MutableStateFlow<Pair<List<Track>, Int>?>(null)
    val pendingPlay: StateFlow<Pair<List<Track>, Int>?> = _pendingPlay.asStateFlow()

    init {
        load()
    }

    fun load() {
        viewModelScope.launch {
            _uiState.value = SourceCollectionUiState(loading = true)
            val found = registry.all.firstOrNull { it.id == sourceId }
            if (found == null) {
                _uiState.value = SourceCollectionUiState(error = "Unknown source: $sourceId")
                return@launch
            }
            source = found
            val tracks = try {
                found.itemsFor(item)
            } catch (e: Exception) {
                Log.w(TAG, "itemsFor failed: ${e.message}")
                _uiState.value = SourceCollectionUiState(error = "Failed to load: ${e.message}")
                return@launch
            }
            Log.i(TAG, "collection '${item.title}': ${tracks.size} track(s)")
            _uiState.value = SourceCollectionUiState(tracks = tracks)
        }
    }

    fun playTrackAt(index: Int) {
        val current = _uiState.value
        if (current.tracks.isEmpty()) return
        val src = source ?: return
        val indexSafe = index.coerceIn(0, current.tracks.size - 1)
        viewModelScope.launch {
            val tracks = registry.resolveQueue(src, current.tracks)
            if (tracks.isNotEmpty()) {
                _pendingPlay.value = tracks to indexSafe.coerceIn(0, tracks.size - 1)
            } else {
                Log.w(TAG, "playTrackAt: nothing resolvable for '${item.title}'")
            }
        }
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }
}