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
import androidx.compose.material3.TextField
import androidx.compose.material3.TextFieldDefaults
import androidx.compose.runtime.Composable
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
import com.roundsalmon4.monochrome.core.api.model.Album
import com.roundsalmon4.monochrome.core.api.model.Artist
import com.roundsalmon4.monochrome.core.api.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SearchScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    viewModel: SearchViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()

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
                                modifier = Modifier.clickable { onAlbumClick(album.id) }
                            )
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
                    }
                }
            }
        }
    }
}
