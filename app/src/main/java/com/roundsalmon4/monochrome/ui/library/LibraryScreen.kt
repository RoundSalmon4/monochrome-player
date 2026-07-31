package com.roundsalmon4.monochrome.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Tab
import androidx.compose.material3.TabRow
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.database.entity.ListenHistoryEntry
import com.roundsalmon4.monochrome.core.database.entity.LocalPlaylist
import com.roundsalmon4.monochrome.core.database.entity.LocalSubscription
import com.roundsalmon4.monochrome.ui.components.AddToPlaylistDialog

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun LibraryScreen(
    onTrackClick: (List<Track>, Int) -> Unit,
    onPlaylistClick: (Long) -> Unit,
    onArtistClick: (String) -> Unit,
    viewModel: LibraryViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsStateWithLifecycle()
    val addToPlaylistEntry by viewModel.addToPlaylistEntry.collectAsStateWithLifecycle()
    var playlistNameInput by remember { mutableStateOf("") }

    if (uiState.showCreatePlaylistDialog) {
        AlertDialog(
            onDismissRequest = { viewModel.dismissCreatePlaylistDialog() },
            title = { Text("Create Playlist") },
            text = {
                OutlinedTextField(
                    value = playlistNameInput, onValueChange = { playlistNameInput = it },
                    label = { Text("Playlist name") }, singleLine = true)
            },
            confirmButton = {
                TextButton(onClick = {
                    if (playlistNameInput.isNotBlank()) {
                        viewModel.createPlaylist(playlistNameInput.trim())
                        playlistNameInput = ""
                    }
                }) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { playlistNameInput = ""; viewModel.dismissCreatePlaylistDialog() }) { Text("Cancel") }
            }
        )
    }

    addToPlaylistEntry?.let { entry ->
        AddToPlaylistDialog(
            trackTitle = entry.title, playlists = uiState.playlists,
            onDismiss = { viewModel.dismissAddToPlaylistDialog() },
            onAddToPlaylist = { viewModel.addToPlaylist(entry, it) },
            onCreatePlaylist = { viewModel.createPlaylistAndAdd(entry, it) }
        )
    }

    Scaffold(
        floatingActionButton = {
            if (uiState.activeTab == LibraryTab.PLAYLISTS) {
                FloatingActionButton(onClick = { viewModel.showCreatePlaylistDialog() }) {
                    Icon(Icons.Default.Add, contentDescription = "Create playlist")
                }
            }
        }
    ) { padding ->
        Column(modifier = Modifier.padding(padding)) {
            TabRow(selectedTabIndex = uiState.activeTab.ordinal) {
                LibraryTab.entries.forEachIndexed { index, tab ->
                    Tab(
                        selected = uiState.activeTab == tab,
                        onClick = { viewModel.switchTab(tab) },
                        text = { Text(when (tab) { LibraryTab.HISTORY -> "History"; LibraryTab.PLAYLISTS -> "Playlists"; LibraryTab.ARTISTS -> "Artists" }) }
                    )
                }
            }

            when (uiState.activeTab) {
                LibraryTab.HISTORY -> HistoryTab(
                    history = uiState.history,
                    onTrackClick = onTrackClick,
                    onDeleteEntry = { viewModel.deleteHistoryEntry(it) },
                    onAddToPlaylist = { viewModel.showAddToPlaylistDialog(it) },
                    onClearAll = { viewModel.clearHistory() }
                )
                LibraryTab.PLAYLISTS -> PlaylistsTab(
                    playlists = uiState.playlists,
                    onPlaylistClick = onPlaylistClick,
                    onDeletePlaylist = { viewModel.deletePlaylist(it) }
                )
                LibraryTab.ARTISTS -> ArtistsTab(
                    subscriptions = uiState.subscriptions,
                    onArtistClick = onArtistClick
                )
            }
        }
    }
}

@Composable
private fun HistoryTab(
    history: List<ListenHistoryEntry>,
    onTrackClick: (List<Track>, Int) -> Unit,
    onDeleteEntry: (String) -> Unit,
    onAddToPlaylist: (ListenHistoryEntry) -> Unit,
    onClearAll: () -> Unit
) {
    if (history.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No listening history", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
        item {
            Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 8.dp),
                horizontalArrangement = Arrangement.End) {
                TextButton(onClick = onClearAll) { Text("Clear all") }
            }
        }
        items(history, key = { it.trackId }) { entry ->
            ListItem(
                headlineContent = { Text(entry.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text(entry.artistName) },
                modifier = Modifier.clickable {
                    val track = Track(
                        id = entry.trackId, title = entry.title, artistName = entry.artistName,
                        artistId = entry.artistId, albumId = "", albumTitle = entry.albumTitle,
                        coverUrl = entry.coverUrl, durationMs = entry.durationMs
                    )
                    onTrackClick(listOf(track), 0)
                }
            )
        }
    }
}

@Composable
private fun PlaylistsTab(
    playlists: List<LocalPlaylist>,
    onPlaylistClick: (Long) -> Unit,
    onDeletePlaylist: (LocalPlaylist) -> Unit
) {
    if (playlists.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No playlists", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
        items(playlists, key = { it.id }) { playlist ->
            ListItem(
                headlineContent = { Text(playlist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                supportingContent = { Text("${playlist.trackCount} tracks") },
                trailingContent = {
                    IconButton(onClick = { onDeletePlaylist(playlist) }) {
                        Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                },
                modifier = Modifier.clickable { onPlaylistClick(playlist.id) }
            )
        }
    }
}

@Composable
private fun ArtistsTab(
    subscriptions: List<LocalSubscription>,
    onArtistClick: (String) -> Unit
) {
    if (subscriptions.isEmpty()) {
        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            Text("No subscribed artists", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    LazyColumn(modifier = Modifier.fillMaxSize(), contentPadding = PaddingValues(bottom = 80.dp)) {
        items(subscriptions, key = { it.artistId }) { sub ->
            ListItem(
                headlineContent = { Text(sub.artistName, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                leadingContent = {
                    AsyncImage(model = sub.thumbnailUrl, contentDescription = null,
                        modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                },
                modifier = Modifier.clickable { onArtistClick(sub.artistId) }
            )
        }
    }
}
