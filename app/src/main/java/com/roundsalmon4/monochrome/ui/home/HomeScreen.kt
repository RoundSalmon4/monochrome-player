package com.roundsalmon4.monochrome.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.material3.pulltorefresh.PullToRefreshBox
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
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.discovery.DiscoveredItem
import com.roundsalmon4.monochrome.ui.common.AlbumAvailabilityViewModel
import com.roundsalmon4.monochrome.ui.common.AlbumStatusDot
import com.roundsalmon4.monochrome.ui.common.SourceDiscoveryViewModel
import com.roundsalmon4.monochrome.ui.common.SourceFeed

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onAlbumClick: (String) -> Unit,
    onArtistClick: (String) -> Unit,
    onPlayItems: (List<Track>, Int) -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
    availabilityViewModel: AlbumAvailabilityViewModel = hiltViewModel(),
    discoveryViewModel: SourceDiscoveryViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    val albumStatus by availabilityViewModel.albumStatus.collectAsStateWithLifecycle()
    val discoveryState by discoveryViewModel.uiState.collectAsStateWithLifecycle()
    val pendingPlay by discoveryViewModel.pendingPlay.collectAsStateWithLifecycle()
    val scrollBehavior = TopAppBarDefaults.exitUntilCollapsedScrollBehavior()

    LaunchedEffect(state.newReleases) {
        availabilityViewModel.checkAlbums(state.newReleases)
    }
    LaunchedEffect(Unit) { viewModel.refresh() }
    LaunchedEffect(Unit) { discoveryViewModel.refresh() }
    LaunchedEffect(pendingPlay) {
        pendingPlay?.let { (tracks, index) ->
            onPlayItems(tracks, index)
            discoveryViewModel.consumePendingPlay()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Home") }, scrollBehavior = scrollBehavior)

        PullToRefreshBox(
            isRefreshing = state.isRefreshing || discoveryState.refreshing,
            onRefresh = {
                viewModel.refresh()
                discoveryViewModel.refresh()
            },
            modifier = Modifier.fillMaxSize()
        ) {
            LazyColumn(contentPadding = PaddingValues(bottom = 80.dp)) {
                if (state.isLoading && state.newReleases.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            CircularProgressIndicator()
                        }
                    }
                } else if (state.error != null && state.newReleases.isEmpty() && discoveryState.sections.isEmpty()) {
                    item {
                        Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                            Text(state.error!!, color = MaterialTheme.colorScheme.error)
                        }
                    }
                } else {
                    if (state.newReleases.isNotEmpty()) {
                        item { SectionHeader("New Releases") }
                        item {
                            LazyRow(
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 4.dp),
                                horizontalArrangement = Arrangement.spacedBy(12.dp)
                            ) {
                                items(state.newReleases, key = { it.id }) { album ->
                                    NewReleaseCard(
                                        album = album,
                                        status = albumStatus[album.id] ?: Availability.UNKNOWN,
                                        onClick = { onAlbumClick(album.id) }
                                    )
                                }
                            }
                        }
                    }

                    if (discoveryState.sections.isNotEmpty()) {
                        discoveryState.sections.forEach { section ->
                            SectionItem(section = section, onPlay = { index ->
                                discoveryViewModel.playItems(section.source, section.items, index)
                            })
                        }
                    } else if (state.newReleases.isEmpty()) {
                        item {
                            Box(
                                modifier = Modifier.fillMaxSize().padding(24.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    "No streaming sources are currently available. Pull to refresh.",
                                    style = MaterialTheme.typography.bodyMedium,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun SectionHeader(title: String) {
    Text(
        text = title,
        style = MaterialTheme.typography.titleMedium,
        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp)
    )
}

@Composable
private fun NewReleaseCard(album: Album, status: Availability, onClick: () -> Unit) {
    Column(
        modifier = Modifier
            .width(140.dp)
            .clip(MaterialTheme.shapes.medium)
            .clickable(onClick = onClick)
    ) {
        Box {
            AsyncImage(
                model = album.coverUrl,
                contentDescription = album.title,
                modifier = Modifier.fillMaxWidth().aspectRatio(1f),
                contentScale = ContentScale.Crop
            )
            AlbumStatusDot(
                status = status,
                modifier = Modifier
                    .align(Alignment.TopStart)
                    .padding(6.dp)
            )
        }
        Text(
            text = album.title,
            style = MaterialTheme.typography.bodyMedium,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(horizontal = 4.dp, vertical = 2.dp)
        )
        Text(
            text = album.artistName,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            modifier = Modifier.padding(start = 4.dp, end = 4.dp)
        )
    }
}

private fun LazyListScope.SectionItem(section: SourceFeed, onPlay: (Int) -> Unit) {
    item { SectionHeader(section.source.displayName) }
    if (section.items.isEmpty()) {
        item {
            Text(
                "No feed from ${section.source.displayName} right now.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 8.dp)
            )
        }
    } else {
        itemsIndexed(section.items, key = { _, item -> item.id }) { index, item ->
            SourceTrackRow(item = item, onClick = { onPlay(index) })
        }
    }
}

@Composable
private fun SourceTrackRow(item: DiscoveredItem, onClick: () -> Unit) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(horizontal = 16.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        AsyncImage(
            model = item.artworkUrl,
            contentDescription = item.title,
            modifier = Modifier.size(48.dp).clip(MaterialTheme.shapes.small),
            contentScale = ContentScale.Crop
        )
        Column(modifier = Modifier.weight(1f)) {
            Text(item.title, style = MaterialTheme.typography.bodyMedium,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
            Text(item.artist, style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
    HorizontalDivider(modifier = Modifier.padding(horizontal = 16.dp))
}