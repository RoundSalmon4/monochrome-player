package com.roundsalmon4.monochrome.ui.common

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.api.Availability
import com.roundsalmon4.monochrome.core.api.TidalApi
import com.roundsalmon4.monochrome.core.api.TrackAvailabilityChecker
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import java.util.concurrent.ConcurrentHashMap
import javax.inject.Inject

/**
 * Shares the [TrackAvailabilityChecker]'s album aggregate status with listing
 * screens and, on request, runs a bounded background pass over the albums
 * currently on screen so their dots fill in without opening each album.
 * Album fetches and track probes are cached in the checker, so repeated passes
 * are cheap and albums aren't re-checked. Gated by the
 * "Check availability in background" setting.
 */
@HiltViewModel
class AlbumAvailabilityViewModel @Inject constructor(
    private val checker: TrackAvailabilityChecker,
    private val tidalApi: TidalApi,
    private val playerPreferences: PlayerPreferences
) : ViewModel() {

    companion object {
        private const val TAG = "ChromePlayer-Availability"
        private const val MAX_CONCURRENT_ALBUMS = 2
    }

    val albumStatus: StateFlow<Map<String, Availability>> = checker.albumStatus

    private val queued = ConcurrentHashMap.newKeySet<String>()
    private val albumSemaphore = Semaphore(MAX_CONCURRENT_ALBUMS)

    /** Queues availability checks for the given on-screen albums, if the setting is enabled. */
    fun checkAlbums(albums: List<Album>) {
        if (albums.isEmpty()) return
        viewModelScope.launch {
            if (!playerPreferences.uiState.first().backgroundAvailability) {
                Log.d(TAG, "background availability checks disabled in settings")
                return@launch
            }
            for (album in albums) {
                if (checker.cachedAlbumStatus(album.id) != Availability.UNKNOWN) continue
                if (!queued.add(album.id)) continue
                launch {
                    albumSemaphore.withPermit {
                        try {
                            Log.d(TAG, "background check album ${album.id} '${album.title}'")
                            val (_, tracks) = tidalApi.getAlbum(album.id)
                            checker.checkAlbum(album.id, tracks)
                        } catch (e: Exception) {
                            Log.w(TAG, "background album check failed for ${album.id}: ${e.message}")
                            queued.remove(album.id)
                        }
                    }
                }
            }
        }
    }
}