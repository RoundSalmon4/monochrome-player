package com.roundsalmon4.monochrome.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.ListItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.roundsalmon4.monochrome.core.api.Availability
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.discovery.DiscoveredKind
import com.roundsalmon4.monochrome.ui.common.AlbumAvailabilityViewModel
import com.roundsalmon4.monochrome.ui.common.AlbumStatusDot

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel(),
    availabilityViewModel: AlbumAvailabilityViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albumStatus by availabilityViewModel.albumStatus.collectAsStateWithLifecycle()
    val pendingSourcePlay by viewModel.pendingSourcePlay.collectAsStateWithLifecycle()

    LaunchedEffect(state.albums) {
        availabilityViewModel.checkAlbums(state.albums)
    }
    LaunchedEffect(pendingSourcePlay) {
        pendingSourcePlay?.let { (tracks, index) ->
            onTrackClick(tracks, index)
            viewModel.consumePendingSourcePlay()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TextField(
            value = state.query,
            onValueChange = { viewModel.onQueryChanged(it) },
            placeholder = { Text("Search artists, albums, tracks") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            singleLine = true,
            modifier = Modifier.fillMaxWidth().padding(8.dp),
            colors = TextFieldDefaults.colors()
        )

        when {
            state.isSearching -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.query.isBlank() -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("Search for music", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            else -> {
                LazyColumn {
                    // Native source results first: everything here is directly playable.
                    state.sourceSections.forEach { section ->
                        item { Text(section.source.displayName, style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                        listOf(DiscoveredKind.TRACK, DiscoveredKind.SET, DiscoveredKind.ARTIST).forEach { kind ->
                            val group = section.items.filter { it.kind == kind }
                            if (group.isEmpty()) return@forEach
                            item { Text(kindLabel(kind), style = MaterialTheme.typography.labelLarge,
                                modifier = Modifier.padding(start = 16.dp, top = 4.dp, bottom = 2.dp)) }
                            items(group, key = { it.id }) { item ->
                                ListItem(
                                    headlineContent = { Text(item.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                    supportingContent = { Text(item.artist) },
                                    leadingContent = {
                                        AsyncImage(model = item.artworkUrl, contentDescription = null,
                                            modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
                                    },
                                    modifier = Modifier.clickable {
                                        if (item.kind == DiscoveredKind.TRACK) {
                                            viewModel.playSourceItems(section.source, section.items, section.items.indexOf(item))
                                        } else {
                                            viewModel.playContainer(section.source, item)
                                        }
                                    }
                                )
                            }
                        }
                    }
                    if (state.artists.isNotEmpty()) {
                        item { Text("Artists", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                        items(state.artists, key = { it.id }) { artist ->
                            ListItem(
                                headlineContent = { Text(artist.name, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                leadingContent = {
                                    AsyncImage(model = artist.imageUrl, contentDescription = null,
                                        modifier = Modifier.size(40.dp).clip(CircleShape), contentScale = ContentScale.Crop)
                                },
                                modifier = Modifier.clickable { onArtistClick(artist.id) }
                            )
                        }
                        item {
                            TextButton(onClick = { viewModel.loadMore(SearchSection.ARTISTS) }) {
                                Text("Show more artists")
                            }
                        }
                    }
                    if (state.albums.isNotEmpty()) {
                        item { Text("Albums", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                        items(state.albums, key = { it.id }) { album ->
                            ListItem(
                                headlineContent = { Text(album.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(album.artistName) },
                                leadingContent = {
                                    AsyncImage(model = album.coverUrl, contentDescription = null,
                                        modifier = Modifier.size(40.dp), contentScale = ContentScale.Crop)
                                },
                                trailingContent = {
                                    AlbumStatusDot(status = albumStatus[album.id] ?: Availability.UNKNOWN)
                                },
                                modifier = Modifier.clickable { onAlbumClick(album.id) }
                            )
                        }
                        item {
                            TextButton(onClick = { viewModel.loadMore(SearchSection.ALBUMS) }) {
                                Text("Show more albums")
                            }
                        }
                    }
                    if (state.tracks.isNotEmpty()) {
                        item { Text("Tracks", style = MaterialTheme.typography.titleSmall,
                            modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)) }
                        items(state.tracks, key = { it.id }) { track ->
                            ListItem(
                                headlineContent = { Text(track.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(track.artistName) },
                                modifier = Modifier.clickable { onTrackClick(state.tracks, state.tracks.indexOf(track)) }
                            )
                        }
                        item {
                            TextButton(onClick = { viewModel.loadMore(SearchSection.TRACKS) }) {
                                Text(if (state.isLoadingMore) "Loading..." else "Show more tracks")
                            }
                        }
                    }
                }
            }
        }
    }
}

private fun kindLabel(kind: DiscoveredKind): String = when (kind) {
    DiscoveredKind.TRACK -> "Tracks"
    DiscoveredKind.SET -> "Sets"
    DiscoveredKind.ARTIST -> "Artists"
}
