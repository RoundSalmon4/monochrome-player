package com.roundsalmon4.monochrome.ui.home

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
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
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
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
import com.roundsalmon4.monochrome.core.discovery.DiscoverySource
import com.roundsalmon4.monochrome.ui.common.AlbumAvailabilityViewModel
import com.roundsalmon4.monochrome.ui.common.AlbumStatusDot
import com.roundsalmon4.monochrome.ui.common.SourceDiscoveryViewModel

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
    LaunchedEffect(Unit) { discoveryViewModel.refreshSources() }
    LaunchedEffect(pendingPlay) {
        pendingPlay?.let { (tracks, index) ->
            onPlayItems(tracks, index)
            discoveryViewModel.consumePendingPlay()
        }
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(title = { Text("Home") }, scrollBehavior = scrollBehavior)

        if (discoveryState.selectedSourceId != null || discoveryState.sources.isNotEmpty()) {
            SourceSelectorRow(
                sources = discoveryState.sources,
                selectedSourceId = discoveryState.selectedSourceId,
                onSelect = discoveryViewModel::selectSource
            )
        }

        val selectedSource = discoveryState.sources.firstOrNull { it.id == discoveryState.selectedSourceId }
        if (selectedSource == null) {
            when {
                state.isLoading -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator()
                    }
                }
                state.error != null && state.newReleases.isEmpty() -> {
                    Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    }
                }
                else -> {
                    PullToRefreshBox(
                        isRefreshing = state.isRefreshing,
                        onRefresh = { viewModel.refresh() },
                        modifier = Modifier.fillMaxSize()
                    ) {
                        LazyVerticalGrid(
                            columns = GridCells.Fixed(2),
                            contentPadding = PaddingValues(8.dp),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            items(state.newReleases, key = { it.id }) { album ->
                                AlbumCard(
                                    album = album,
                                    status = albumStatus[album.id] ?: Availability.UNKNOWN,
                                    onClick = { onAlbumClick(album.id) }
                                )
                            }
                        }
                    }
                }
            }
        } else {
            SourceFeed(
                source = selectedSource,
                loading = discoveryState.loadingFeed,
                items = discoveryState.feed,
                onItemClick = { index ->
                    discoveryViewModel.playItems(selectedSource, discoveryState.feed, index)
                }
            )
        }
    }
}

@Composable
private fun SourceSelectorRow(
    sources: List<DiscoverySource>,
    selectedSourceId: String?,
    onSelect: (String?) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        FilterChip(
            selected = selectedSourceId == null,
            onClick = { onSelect(null) },
            label = { Text("Auto") }
        )
        sources.forEach { source ->
            FilterChip(
                selected = selectedSourceId == source.id,
                onClick = { onSelect(source.id) },
                label = { Text(source.displayName) }
            )
        }
    }
}

@Composable
private fun SourceFeed(
    source: DiscoverySource,
    loading: Boolean,
    items: List<com.roundsalmon4.monochrome.core.discovery.DiscoveredItem>,
    onItemClick: (Int) -> Unit
) {
    when {
        loading -> Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            CircularProgressIndicator()
        }
        items.isEmpty() -> Box(
            modifier = Modifier.fillMaxSize().padding(24.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(
                "${source.displayName} has no feed right now. Use Search to explore it.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        else -> LazyColumn(contentPadding = PaddingValues(vertical = 4.dp)) {
            itemsIndexed(items, key = { _, item -> item.id }) { index, item ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clickable { onItemClick(index) }
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
        }
    }
}

@Composable
private fun AlbumCard(album: Album, status: Availability, onClick: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)
    ) {
        Column {
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
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
            )
            Text(
                text = album.artistName,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.padding(start = 8.dp, end = 8.dp, bottom = 8.dp)
            )
        }
    }
}
