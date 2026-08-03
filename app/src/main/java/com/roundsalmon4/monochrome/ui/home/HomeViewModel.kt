package com.roundsalmon4.monochrome.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.model.Album
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val newReleases: List<Album> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tidalApi: TidalApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Reloads new releases. Shows a full-screen spinner when there's no data yet, otherwise a pull-to-refresh indicator. */
    fun refresh() {
        viewModelScope.launch {
            val hasData = _uiState.value.newReleases.isNotEmpty()
            _uiState.value = _uiState.value.copy(
                isLoading = !hasData,
                isRefreshing = hasData,
                error = null
            )
            try {
                val results = tidalApi.searchAlbums("new")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    newReleases = results.take(50)
                )
            } catch (e: Exception) {
                Log.e("ChromePlayer", "Home refresh failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    error = e.message ?: "Failed to load"
                )
            }
        }
    }
}
