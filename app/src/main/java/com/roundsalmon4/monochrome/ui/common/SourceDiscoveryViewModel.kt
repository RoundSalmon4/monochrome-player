package com.roundsalmon4.monochrome.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.discovery.DiscoveredItem
import com.roundsalmon4.monochrome.core.discovery.DiscoverySource
import com.roundsalmon4.monochrome.core.discovery.SourceDiscoveryRegistry
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import javax.inject.Inject

data class SourceFeed(
    val source: DiscoverySource,
    val items: List<DiscoveredItem>
)

/**
 * Loads the Home feed from EVERY source that is currently available, so Home is
 * automatically composed of all working backends (no manual source selection).
 * Each source's content is a labeled section; playback is resolved directly from
 * the owning source so everything surfaced is playable.
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
        val sections: List<SourceFeed> = emptyList(),
        val sources: List<DiscoverySource> = emptyList(),
        val refreshing: Boolean = false
    )

    private val _uiState = MutableStateFlow(UiState())
    val uiState: StateFlow<UiState> = _uiState.asStateFlow()

    private val _pendingPlay = MutableStateFlow<Pair<List<Track>, Int>?>(null)
    val pendingPlay: StateFlow<Pair<List<Track>, Int>?> = _pendingPlay.asStateFlow()

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _uiState.update { it.copy(refreshing = true) }
            val sources = registry.available()
            val feeds = coroutineScope {
                sources.map { source ->
                    async {
                        val items = try {
                            source.homeFeed(FEED_LIMIT)
                        } catch (e: Exception) {
                            Log.w(TAG, "feed failed for ${source.displayName}: ${e.message}")
                            emptyList()
                        }
                        SourceFeed(source, items)
                    }
                }.awaitAll()
            }
            _uiState.update { it.copy(sections = feeds, sources = sources, refreshing = false) }
            Log.i(TAG, "home feed sections: ${feeds.joinToString { "${it.source.displayName}=${it.items.size}" }}")
        }
    }

    fun consumePendingPlay() {
        _pendingPlay.value = null
    }

    /** Resolve [items] of [source] into direct-play tracks and request playback of [startIndex]. */
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