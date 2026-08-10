package com.roundsalmon4.monochrome.ui.home

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.JsonArray
import com.google.gson.JsonObject
import com.google.gson.JsonParser
import com.roundsalmon4.monochrome.core.api.model.Album
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import javax.inject.Inject

data class HomeUiState(
    val isLoading: Boolean = false,
    val isRefreshing: Boolean = false,
    val newReleases: List<Album> = emptyList(),
    val error: String? = null
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    private val okHttpClient: OkHttpClient
) : ViewModel() {

    private val _uiState = MutableStateFlow(HomeUiState())
    val uiState: StateFlow<HomeUiState> = _uiState.asStateFlow()

    /** Reloads trending/new albums. Shows a full-screen spinner when there's no data yet, otherwise a pull-to-refresh indicator. */
    fun refresh() {
        viewModelScope.launch {
            val hasData = _uiState.value.newReleases.isNotEmpty()
            _uiState.value = _uiState.value.copy(
                isLoading = !hasData,
                isRefreshing = hasData,
                error = null
            )
            try {
                val albums = fetchTopAlbums()
                _uiState.value = _uiState.value.copy(
                    isLoading = false,
                    isRefreshing = false,
                    newReleases = albums
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

    private suspend fun fetchTopAlbums(): List<Album> = withContext(Dispatchers.IO) {
        val request = okhttp3.Request.Builder()
            .url("https://hot.monochrome.tf/")
            .header("User-Agent", "ChromePlayer/0.1")
            .build()
        okHttpClient.newCall(request).execute().use { resp ->
            if (!resp.isSuccessful) throw RuntimeException("Home feed HTTP ${resp.code}")
            val body = resp.body?.string() ?: return@use emptyList()
            val root = runCatching { JsonParser.parseString(body).asJsonObject }.getOrNull()
                ?: throw RuntimeException("Invalid home feed")
            val albums = root.getAsJsonArray("top_albums") ?: JsonArray()
            albums.mapNotNull { el ->
                val o = el.asJsonObject
                val id = o.get("id")?.asLong ?: return@mapNotNull null
                val artist = firstArtist(o)
                Album(
                    id = id.toString(),
                    title = o.get("title")?.asString ?: "Unknown Album",
                    artistName = artist?.get("name")?.asString ?: "Unknown Artist",
                    artistId = artist?.get("id")?.asLong?.toString() ?: "",
                    coverUrl = coverUrl(o.get("cover")?.asString),
                    year = o.get("releaseDate")?.asString?.take(4)?.toIntOrNull() ?: 0,
                    trackCount = o.get("numberOfTracks")?.asInt ?: 0,
                    durationMs = (o.get("duration")?.asLong ?: 0L) * 1000L
                )
            }
        }
    }

    private fun firstArtist(album: JsonObject): JsonObject? {
        val artists = album.getAsJsonArray("artists") ?: return null
        if (artists.size() == 0) return null
        return runCatching { artists[0].asJsonObject }.getOrNull()
    }

    private fun coverUrl(cover: String?): String {
        if (cover.isNullOrBlank()) return ""
        if (cover.startsWith("http")) return cover
        return "https://resources.tidal.com/images/${cover.replace("-", "/")}/640x640.jpg"
    }
}
