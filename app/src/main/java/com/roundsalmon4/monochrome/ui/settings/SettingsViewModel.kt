package com.roundsalmon4.monochrome.ui.settings

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.roundsalmon4.monochrome.core.database.ExportData
import com.roundsalmon4.monochrome.core.database.HistoryDao
import com.roundsalmon4.monochrome.core.database.LocalPlaylistExport
import com.roundsalmon4.monochrome.core.database.LocalSubscriptionExport
import com.roundsalmon4.monochrome.core.database.PlaylistDao
import com.roundsalmon4.monochrome.core.database.PlaylistTrackExport
import com.roundsalmon4.monochrome.core.database.PreferencesExport
import com.roundsalmon4.monochrome.core.database.SubscriptionDao
import com.roundsalmon4.monochrome.core.database.entity.LocalPlaylist
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import com.roundsalmon4.monochrome.core.datastore.PreferencesUiState
import dagger.hilt.android.lifecycle.HiltViewModel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.json.Json
import javax.inject.Inject

@HiltViewModel
class SettingsViewModel @Inject constructor(
    private val playerPreferences: PlayerPreferences,
    private val historyDao: HistoryDao,
    private val playlistDao: PlaylistDao,
    private val subscriptionDao: SubscriptionDao
) : ViewModel() {

    private val _uiState = MutableStateFlow(PreferencesUiState())
    val uiState: StateFlow<PreferencesUiState> = _uiState.asStateFlow()

    private val _showClearHistoryDialog = MutableStateFlow(false)
    val showClearHistoryDialog: StateFlow<Boolean> = _showClearHistoryDialog.asStateFlow()

    private val _showClearPlaylistsDialog = MutableStateFlow(false)
    val showClearPlaylistsDialog: StateFlow<Boolean> = _showClearPlaylistsDialog.asStateFlow()

    private val _exportResult = MutableStateFlow<String?>(null)
    val exportResult: StateFlow<String?> = _exportResult.asStateFlow()

    private val _importResult = MutableStateFlow<String?>(null)
    val importResult: StateFlow<String?> = _importResult.asStateFlow()

    init {
        viewModelScope.launch {
            playerPreferences.uiState.collect { _uiState.value = it }
        }
    }

    fun clearExportResult() { _exportResult.value = null }
    fun clearImportResult() { _importResult.value = null }

    suspend fun buildExportJson(): String {
        val prefs = playerPreferences.uiState.first()
        val playlists = playlistDao.getAllPlaylists().first()
        val subscriptions = subscriptionDao.getAll().first()

        val exportData = ExportData(
            preferences = PreferencesExport(
                playbackSpeed = prefs.playbackSpeed, defaultQuality = prefs.defaultQuality,
                resumePlayback = prefs.resumePlayback, showMiniPlayer = prefs.showMiniPlayer,
                themeMode = prefs.themeMode, useAmoledTheme = prefs.useAmoledTheme,
                primaryColor = prefs.primaryColor, secondaryColor = prefs.secondaryColor,
                colorSchemeMode = prefs.colorSchemeMode, pipEnabled = prefs.pipEnabled
            ),
            playlists = playlists.map { playlist ->
                val tracks = playlistDao.getPlaylistTracksSync(playlist.id)
                LocalPlaylistExport(
                    name = playlist.name, createdAt = playlist.createdAt,
                    tracks = tracks.map { t ->
                        PlaylistTrackExport(
                            trackId = t.trackId, title = t.title, artistName = t.artistName,
                            coverUrl = t.coverUrl, durationMs = t.durationMs, position = t.position
                        )
                    }
                )
            },
            subscriptions = subscriptions.map { sub ->
                LocalSubscriptionExport(
                    artistId = sub.artistId, artistName = sub.artistName,
                    thumbnailUrl = sub.thumbnailUrl, subscribedAt = sub.subscribedAt
                )
            }
        )

        return withContext(Dispatchers.IO) {
            Json { prettyPrint = true }.encodeToString(ExportData.serializer(), exportData)
        }
    }

    fun importFromJson(json: String) {
        viewModelScope.launch {
            try {
                val data = withContext(Dispatchers.IO) {
                    Json { ignoreUnknownKeys = true }.decodeFromString(ExportData.serializer(), json)
                }

                data.preferences?.let { p ->
                    playerPreferences.setPlaybackSpeed(p.playbackSpeed)
                    playerPreferences.setDefaultQuality(p.defaultQuality)
                    playerPreferences.setResumePlayback(p.resumePlayback)
                    playerPreferences.setShowMiniPlayer(p.showMiniPlayer)
                    playerPreferences.setThemeMode(p.themeMode)
                    playerPreferences.setUseAmoledTheme(p.useAmoledTheme)
                    playerPreferences.setPrimaryColor(p.primaryColor)
                    playerPreferences.setSecondaryColor(p.secondaryColor)
                    playerPreferences.setColorSchemeMode(p.colorSchemeMode)
                    playerPreferences.setPiPEnabled(p.pipEnabled)
                }

                data.subscriptions?.forEach { sub ->
                    subscriptionDao.subscribe(
                        com.roundsalmon4.monochrome.core.database.entity.LocalSubscription(
                            artistId = sub.artistId, artistName = sub.artistName,
                            thumbnailUrl = sub.thumbnailUrl, subscribedAt = sub.subscribedAt
                        )
                    )
                }

                data.playlists?.forEach { playlistData ->
                    val id = playlistDao.insertPlaylist(
                        LocalPlaylist(name = playlistData.name, createdAt = playlistData.createdAt)
                    )
                    playlistData.tracks.forEachIndexed { index, track ->
                        playlistDao.insertTrack(
                            com.roundsalmon4.monochrome.core.database.entity.PlaylistTrack(
                                playlistId = id, trackId = track.trackId, title = track.title,
                                artistName = track.artistName, coverUrl = track.coverUrl,
                                durationMs = track.durationMs, position = track.position
                            )
                        )
                    }
                    playlistDao.updatePlaylist(
                        LocalPlaylist(id = id, name = playlistData.name, createdAt = playlistData.createdAt, trackCount = playlistData.tracks.size)
                    )
                }

                _importResult.value = "Import complete"
            } catch (e: Exception) {
                _importResult.value = "Import failed: ${e.message?.take(100)}"
            }
        }
    }

    fun setPlaybackSpeed(speed: Float) = viewModelScope.launch { playerPreferences.setPlaybackSpeed(speed) }
    fun setDefaultQuality(quality: String) = viewModelScope.launch { playerPreferences.setDefaultQuality(quality) }
    fun setResumePlayback(enabled: Boolean) = viewModelScope.launch { playerPreferences.setResumePlayback(enabled) }
    fun setShowMiniPlayer(enabled: Boolean) = viewModelScope.launch { playerPreferences.setShowMiniPlayer(enabled) }
    fun setThemeMode(mode: String) = viewModelScope.launch { playerPreferences.setThemeMode(mode) }
    fun setUseAmoledTheme(enabled: Boolean) = viewModelScope.launch { playerPreferences.setUseAmoledTheme(enabled) }
    fun setPrimaryColor(color: Int) = viewModelScope.launch { playerPreferences.setPrimaryColor(color) }
    fun setSecondaryColor(color: Int) = viewModelScope.launch { playerPreferences.setSecondaryColor(color) }
    fun setColorSchemeMode(mode: String) = viewModelScope.launch { playerPreferences.setColorSchemeMode(mode) }
    fun setPiPEnabled(enabled: Boolean) = viewModelScope.launch { playerPreferences.setPiPEnabled(enabled) }

    fun showClearHistoryDialog() { _showClearHistoryDialog.value = true }
    fun dismissClearHistoryDialog() { _showClearHistoryDialog.value = false }
    fun showClearPlaylistsDialog() { _showClearPlaylistsDialog.value = true }
    fun dismissClearPlaylistsDialog() { _showClearPlaylistsDialog.value = false }

    fun clearHistory() = viewModelScope.launch {
        historyDao.clearAll()
        _showClearHistoryDialog.value = false
    }

    fun clearPlaylists() = viewModelScope.launch {
        val playlists = playlistDao.getAllPlaylists().first()
        for (p in playlists) { playlistDao.clearPlaylist(p.id); playlistDao.deletePlaylist(p) }
        _showClearPlaylistsDialog.value = false
    }
}
