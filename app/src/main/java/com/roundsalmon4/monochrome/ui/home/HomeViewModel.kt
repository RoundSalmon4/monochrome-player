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
    val isLoading: Boolean = true,
    val newReleases: List<Album> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val tidalApi: TidalApi
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    init {
        loadNewReleases()
    }

    private fun loadNewReleases() {
        viewModelScope.launch {
            _uiState.value = _uiState.value.copy(isLoading = true, error = null)
            try {
                val results = tidalApi.searchAlbums("new")
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    newReleases = results.take(50)
                )
            } catch (e: Exception) {
                Log.e("ChromePlayer", "Home loadNewReleases failed", e)
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    error = e.message ?: "Failed to load"
                )
            }
        }
    }
}
