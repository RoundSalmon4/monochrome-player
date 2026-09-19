package com.roundsalmon4.monochrome.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.discovery.DiscoveredItem
import com.roundsalmon4.monochrome.core.discovery.DiscoverySource
import com.roundsalmon4.monochrome.core.discovery.SourceDiscoveryRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

/**
 * Drives the generic source-mode UI: lists the [DiscoverySource]s that are
 * currently available, lets the user pick one (or stay on "Auto"), loads that
 * source's native feed, and resolves items into playable queues.
 */
@HiltViewModel
class SourceDiscoveryViewModel @Inject constructor(
    private val registry: SourceDiscoveryRegistry
) : ViewModel() {

    companion object {
        private const val TAG = "ChromePlayer-Discovery"
        private const val FEED_LIMIT = 20
    }

    data class UiState(
        val sources: List<DiscoverySource> = emptyList(),
        val selectedSourceId: String? = null,
        val feed: List<DiscoveredItem> = emptyList(),
        val loadingFeed: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _pendingPlay = MutableStateFlow<Pair<List<Track>, Int>?>(null)
    val pendingPlay: StateFlow<Pair<List<Track>, Int>?> = _pendingPlay.asStateFlow()

    private var feedJob: Job? = null

    init {
        refreshSources()
    }

    fun refreshSources() {
        viewModelScope.launch {
            val sources = registry.available()
            _uiState.update { it.copy(sources = sources) }
            val selected = _uiState.value.selectedSourceId
            if (selected != null && sources.none { it.id == selected }) {
                _uiState.update { it.copy(selectedSourceId = null) }
            }
            loadFeed()
        }
    }

    fun selectSource(id: String?) {
        if (id == _uiState.value.selectedSourceId) return
        _uiState.update { it.copy(selectedSourceId = id) }
        loadFeed()
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }

    private fun loadFeed() {
        val selectedId = _uiState.value.selectedSourceId ?: return
        val source = _uiState.value.sources.firstOrNull { it.id == selectedId } ?: return
        feedJob?.cancel()
        feedJob = viewModelScope.launch {
            _uiState.update { it.copy(loadingFeed = true, feed = emptyList()) }
            val items = try {
                source.homeFeed(FEED_LIMIT)
            } catch (e: Exception) {
                Log.w(TAG, "feed failed for ${source.displayName}: ${e.message}")
                emptyList()
            }
            _uiState.update { it.copy(feed = items, loadingFeed = false) }
        }
    }

    /** Resolve [items] into direct-play tracks and request playback of [startIndex]. */
    fun playItems(source: DiscoverySource, items: List<DiscoveredItem>, startIndex: Int) {
        if (items.isEmpty()) return
        viewModelScope.launch {
            val tracks = registry.resolveQueue(source, items)
            if (tracks.isNotEmpty()) {
                _pendingPlay.value = tracks to startIndex.coerceIn(0, tracks.size - 1)
            }
        }
    }
}