package com.roundsalmon4.monochrome.ui.album

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.roundsalmon4.monochrome.core.api.Availability
import com.roundsalmon4.monochrome.core.api.model.Track

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AlbumDetailScreen(
    albumId: String,
    onBackClick: () -> Unit,
    onTrackClick: (List<Track>, Int) -> Unit,
    onShuffleClick: (List<Track>) -> Unit,
    viewModel: AlbumDetailViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val trackStatus by viewModel.trackStatus.collectAsStateWithLifecycle()
    val albumStatus by viewModel.albumStatus.collectAsStateWithLifecycle()

    LaunchedEffect(albumId) { viewModel.loadAlbum(albumId) }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text(state.album?.title ?: "Album") },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )

        when {
            state.isLoading -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            state.error != null -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                }
            }
            state.album != null -> {
                LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                    item {
                        Row(
                            modifier = Modifier.fillMaxWidth().padding(16.dp),
                            horizontalArrangement = Arrangement.spacedBy(16.dp)
                        ) {
                            AsyncImage(
                                model = state.album!!.coverUrl,
                                contentDescription = state.album!!.title,
                                modifier = Modifier.weight(0.4f).aspectRatio(1f),
                                contentScale = ContentScale.Crop
                            )
                            Column(modifier = Modifier.weight(0.6f)) {
                                Text(state.album!!.title, style = MaterialTheme.typography.titleLarge,
                                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                                Text(state.album!!.artistName, style = MaterialTheme.typography.bodyLarge,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Text("${state.tracks.size} tracks", style = MaterialTheme.typography.bodySmall,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant)
                                Spacer(modifier = Modifier.padding(top = 4.dp))
                                AlbumAvailabilityIndicator(albumStatus[state.album!!.id] ?: Availability.UNKNOWN)
                            }
                        }
                        Row(modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                            Button(
                                onClick = { if (state.tracks.isNotEmpty()) onTrackClick(state.tracks, 0) },
                                enabled = state.tracks.isNotEmpty()
                            ) {
                                Icon(Icons.Default.PlayArrow, contentDescription = null)
                                Text("Play", modifier = Modifier.padding(start = 4.dp))
                            }
                            Button(
                                onClick = { if (state.tracks.isNotEmpty()) onShuffleClick(state.tracks) },
                                enabled = state.tracks.isNotEmpty()
                            ) {
                                Icon(Icons.Default.Shuffle, contentDescription = null)
                                Text("Shuffle", modifier = Modifier.padding(start = 4.dp))
                            }
                        }
                    }

                    itemsIndexed(state.tracks, key = { _, t -> t.id }) { index, track ->
                        HorizontalDivider()
                        Row(
                            modifier = Modifier.fillMaxWidth()
                                .clickable { onTrackClick(state.tracks, index) }
                                .padding(horizontal = 16.dp, vertical = 12.dp),
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            Text("${index + 1}", style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onSurfaceVariant)
                            Column(modifier = Modifier.weight(1f)) {
                                Text(track.title, style = MaterialTheme.typography.bodyMedium,
                                    maxLines = 1, overflow = TextOverflow.Ellipsis)
                            }
                            AvailabilityDot(trackStatus[track.id])
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun AvailabilityDot(available: Boolean?) {
    val color = when (available) {
        true -> Color(0xFF2E7D32)
        false -> MaterialTheme.colorScheme.error
        null -> MaterialTheme.colorScheme.outlineVariant
    }
    Box(
        modifier = Modifier
            .size(10.dp)
            .background(color, CircleShape)
    )
}

@Composable
private fun AlbumAvailabilityIndicator(status: Availability) {
    val color = when (status) {
        Availability.ALL_AVAILABLE -> Color(0xFF2E7D32)
        Availability.SOME_AVAILABLE -> Color(0xFFF9A825)
        Availability.NONE_AVAILABLE -> MaterialTheme.colorScheme.error
        Availability.UNKNOWN -> MaterialTheme.colorScheme.outline
    }
    val label = when (status) {
        Availability.ALL_AVAILABLE -> "All tracks available"
        Availability.SOME_AVAILABLE -> "Some tracks available"
        Availability.NONE_AVAILABLE -> "No tracks available"
        Availability.UNKNOWN -> "Checking availability..."
    }
    Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        Box(
            modifier = Modifier
                .size(10.dp)
                .background(color, CircleShape)
        )
        Text(label, style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
