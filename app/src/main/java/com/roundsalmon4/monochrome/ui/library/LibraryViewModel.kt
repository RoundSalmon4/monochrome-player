package com.roundsalmon4.monochrome.ui.library

import android.util.Log
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.database.HistoryDao
import com.roundsalmon4.monochrome.core.database.PlaylistDao
import com.roundsalmon4.monochrome.core.database.SubscriptionDao
import com.roundsalmon4.monochrome.core.database.entity.ListenHistoryEntry
import com.roundsalmon4.monochrome.core.database.entity.LocalPlaylist
import com.roundsalmon4.monochrome.core.database.entity.LocalSubscription
import com.roundsalmon4.monochrome.core.database.entity.PlaylistTrack
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import javax.inject.Inject

enum class LibraryTab { HISTORY, PLAYLISTS, ARTISTS }

data class LibraryUiState(
    val activeTab: LibraryTab = LibraryTab.HISTORY,
    val history: List<ListenHistoryEntry> = emptyList(),
    val playlists: List<LocalPlaylist> = emptyList(),
    val subscriptions: List<LocalSubscription> = emptyList(),
    val showCreatePlaylistDialog: Boolean = false
)

@HiltViewModel
class LibraryViewModel @Inject constructor(
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val subscriptionDao: SubscriptionDao
) : ViewModel() {

    private val _activeTab = MutableStateFlow(LibraryTab.HISTORY)
    private val _showCreatePlaylistDialog = MutableStateFlow(false)
    private val _addToPlaylistEntry = MutableStateFlow<ListenHistoryEntry?>(null)
    val addToPlaylistEntry: StateFlow<ListenHistoryEntry?> = _addToPlaylistEntry.asStateFlow()

    val uiState: StateFlow<LibraryUiState> = combine(
        _activeTab, historyDao.getAll(), playlistDao.getAllPlaylists(),
        subscriptionDao.getAll(), _showCreatePlaylistDialog
    ) { tab, history, playlists, subscriptions, showDialog ->
        LibraryUiState(
            activeTab = tab, history = history, playlists = playlists,
            subscriptions = subscriptions, showCreatePlaylistDialog = showDialog
        )
    }.stateIn(scope = viewModelScope, started = SharingStarted.WhileSubscribed(5000), initialValue = LibraryUiState())

    fun switchTab(tab: LibraryTab) { _activeTab.value = tab }

    fun createPlaylist(name: String) {
        viewModelScope.launch {
            playlistDao.insertPlaylist(LocalPlaylist(name = name, createdAt = System.currentTimeMillis()))
            _showCreatePlaylistDialog.value = false
        }
    }

    fun showCreatePlaylistDialog() { _showCreatePlaylistDialog.value = true }
    fun dismissCreatePlaylistDialog() { _showCreatePlaylistDialog.value = false }

    fun deletePlaylist(playlist: LocalPlaylist) {
        viewModelScope.launch { playlistDao.deletePlaylist(playlist) }
    }

    fun deleteHistoryEntry(trackId: String) {
        viewModelScope.launch { historyDao.delete(trackId) }
    }

    fun clearHistory() {
        viewModelScope.launch { historyDao.clearAll() }
    }

    fun showAddToPlaylistDialog(entry: ListenHistoryEntry) { _addToPlaylistEntry.value = entry }
    fun dismissAddToPlaylistDialog() { _addToPlaylistEntry.value = null }

    fun addToPlaylist(entry: ListenHistoryEntry, playlist: LocalPlaylist) {
        viewModelScope.launch {
            try {
                val count = playlistDao.getTrackCount(playlist.id)
                playlistDao.insertTrack(
                    PlaylistTrack(
                        playlistId = playlist.id, trackId = entry.trackId,
                        title = entry.title, artistName = entry.artistName,
                        albumTitle = entry.albumTitle, coverUrl = entry.coverUrl,
                        durationMs = entry.durationMs, position = count
                    )
                )
                playlistDao.updatePlaylist(playlist.copy(trackCount = count + 1))
                _addToPlaylistEntry.value = null
            } catch (e: Exception) {
                Log.e("ChromePlayer", "addToPlaylist failed", e)
            }
        }
    }

    fun createPlaylistAndAdd(entry: ListenHistoryEntry, name: String) {
        viewModelScope.launch {
            try {
                val id = playlistDao.insertPlaylist(LocalPlaylist(name = name, createdAt = System.currentTimeMillis()))
                playlistDao.insertTrack(
                    PlaylistTrack(
                        playlistId = id, trackId = entry.trackId, title = entry.title,
                        artistName = entry.artistName, albumTitle = entry.albumTitle,
                        coverUrl = entry.coverUrl, durationMs = entry.durationMs, position = 0
                    )
                )
                playlistDao.updatePlaylist(
                    LocalPlaylist(id = id, name = name, createdAt = System.currentTimeMillis(), trackCount = 1)
                )
                _addToPlaylistEntry.value = null
            } catch (e: Exception) {
                Log.e("ChromePlayer", "createPlaylistAndAdd failed", e)
            }
        }
    }


}
