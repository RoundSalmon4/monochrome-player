package com.roundsalmon4.monochrome.ui.search

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.Track
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class SearchSection { TRACKS, ALBUMS, ARTISTS }

data class SearchUiState(
    val query: String = "",
    val isSearching: Boolean = false,
    val tracks: List<Track> = emptyList(),
    val albums: List<Album> = emptyList(),
    val artists: List<Artist> = emptyList(),
    val error: String? = null,
    val isLoadingMore: Boolean = false
)

@HiltViewModel
class SearchViewModel @Inject constructor(
    private val tidalApi: TidalApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(SearchUiState())
    val uiState: StateFlow<SearchUiState> = _uiState.asStateFlow()

    private var searchJob: Job? = null
    private var tracksOffset = 0
    private var albumsOffset = 0
    private var artistsOffset = 0

    fun onQueryChanged(query: String) {
        _uiState.value = _uiState.value.copy(query = query)
        searchJob?.cancel()
        if (query.isBlank()) {
            _uiState.value = SearchUiState()
            return
        }
        searchJob = viewModelScope.launch {
            delay(300)
            performSearch(query)
        }
    }

    private suspend fun performSearch(query: String) {
        _uiState.value = _uiState.value.copy(isSearching = true, error = null)
        try {
            val results = tidalApi.search(query)
            tracksOffset = results.tracks.size
            albumsOffset = results.albums.size
            artistsOffset = results.artists.size
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                tracks = results.tracks,
                albums = results.albums,
                artists = results.artists
            )
        } catch (e: kotlinx.coroutines.CancellationException) {
            throw e
        } catch (e: Exception) {
            Log.e("ChromePlayer", "Search failed for query=$query", e)
            _uiState.value = _uiState.value.copy(
                isSearching = false,
                error = e.message ?: "Search failed"
            )
        }
    }

    fun loadMore(section: SearchSection) {
        val query = _uiState.value.query
        if (query.isBlank() || _uiState.value.isLoadingMore) return
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoadingMore = true)
            try {
                when (section) {
                    SearchSection.TRACKS -> {
                        val more = tidalApi.searchTracks(query, tracksOffset)
                        tracksOffset += more.size
                        if (more.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(tracks = _uiState.value.tracks + more)
                        }
                    }
                    SearchSection.ALBUMS -> {
                        val more = tidalApi.searchAlbums(query, albumsOffset)
                        albumsOffset += more.size
                        if (more.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(albums = _uiState.value.albums + more)
                        }
                    }
                    SearchSection.ARTISTS -> {
                        val more = tidalApi.searchArtists(query, artistsOffset)
                        artistsOffset += more.size
                        if (more.isNotEmpty()) {
                            _uiState.value = _uiState.value.copy(artists = _uiState.value.artists + more)
                        }
                    }
                }
            } catch (e: Exception) {
                Log.w("ChromePlayer", "Load more failed for section=$section", e)
            }
            _uiState.value = _uiState.value.copy(isLoadingMore = false)
        }
    }
}
