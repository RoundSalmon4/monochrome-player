package com.roundsalmon4.monochrome.ui.player

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Bedtime
import androidx.compose.material.icons.filled.Forward30
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material.icons.filled.Repeat
import androidx.compose.material.icons.filled.RepeatOne
import androidx.compose.material.icons.filled.Replay10
import androidx.compose.material.icons.filled.Shuffle
import androidx.compose.material.icons.filled.SkipNext
import androidx.compose.material.icons.filled.SkipPrevious
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.VolumeDown
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil3.compose.AsyncImage
import com.roundsalmon4.monochrome.player.RepeatMode

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun PlayerScreen(
    onBackClick: () -> Unit,
    viewModel: PlayerViewModel = hiltViewModel()
) {
    val state by viewModel.uiState.collectAsStateWithLifecycle()
    var showSpeedSheet by remember { mutableStateOf(false) }
    var showSleepTimerDialog by remember { mutableStateOf(false) }

    if (showSpeedSheet) {
        SpeedPickerSheet(
            currentSpeed = state.playbackSpeed,
            onSpeedSelected = { viewModel.setPlaybackSpeed(it) },
            onDismiss = { showSpeedSheet = false }
        )
    }

    if (showSleepTimerDialog) {
        AlertDialog(
            onDismissRequest = { showSleepTimerDialog = false },
            title = { Text("Sleep Timer") },
            text = {
                Column {
                    SleepTimerOption(15, state.sleepTimerMinutes, viewModel) { showSleepTimerDialog = false }
                    SleepTimerOption(30, state.sleepTimerMinutes, viewModel) { showSleepTimerDialog = false }
                    SleepTimerOption(45, state.sleepTimerMinutes, viewModel) { showSleepTimerDialog = false }
                    SleepTimerOption(60, state.sleepTimerMinutes, viewModel) { showSleepTimerDialog = false }
                }
            },
            confirmButton = {
                TextButton(onClick = {
                    viewModel.setSleepTimer(0)
                    showSleepTimerDialog = false
                }) { Text("Cancel timer") }
            },
            dismissButton = {
                TextButton(onClick = { showSleepTimerDialog = false }) { Text("Close") }
            }
        )
    }

    Column(modifier = Modifier.fillMaxSize()) {
        TopAppBar(
            title = { Text("Now Playing") },
            navigationIcon = {
                IconButton(onClick = onBackClick) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            },
            actions = {
                if (state.sleepTimerMinutes > 0) {
                    Text(
                        "${state.sleepTimerMinutes}m",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.primary
                    )
                }
                IconButton(onClick = { showSleepTimerDialog = true }) {
                    Icon(Icons.Default.Bedtime, contentDescription = "Sleep timer")
                }
                IconButton(onClick = { showSpeedSheet = true }) {
                    Icon(Icons.Default.Speed, contentDescription = "Speed")
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
                Column(
                    modifier = Modifier.fillMaxSize().padding(24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Text(state.error!!, color = MaterialTheme.colorScheme.error)
                    Button(onClick = { viewModel.retry() }, modifier = Modifier.padding(top = 16.dp)) {
                        Text("Retry")
                    }
                }
            }
            state.currentTrack != null -> {
                val track = state.currentTrack!!
                Column(
                    modifier = Modifier.fillMaxSize().padding(horizontal = 24.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    AsyncImage(
                        model = track.coverUrl, contentDescription = track.title,
                        modifier = Modifier.fillMaxWidth().aspectRatio(1f).padding(top = 24.dp),
                        contentScale = ContentScale.Crop
                    )

                    Text(track.title, style = MaterialTheme.typography.titleLarge,
                        maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.padding(top = 24.dp))

                    Text(track.artistName, style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    Text(track.albumTitle, style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)

                    ProgressSlider(state, viewModel)

                    Row(
                        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
                        horizontalArrangement = Arrangement.SpaceEvenly,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = { viewModel.toggleShuffle() }) {
                            Icon(
                                Icons.Default.Shuffle,
                                contentDescription = "Shuffle",
                                tint = if (state.isShuffleEnabled) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.previousTrack() }) {
                            Icon(Icons.Default.SkipPrevious, contentDescription = "Previous", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { viewModel.seekBackward() }) {
                            Icon(Icons.Default.Replay10, contentDescription = "Rewind 10s", modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { viewModel.togglePlayPause() }) {
                            Icon(
                                if (state.isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                                contentDescription = if (state.isPlaying) "Pause" else "Play",
                                modifier = Modifier.size(72.dp)
                            )
                        }
                        IconButton(onClick = { viewModel.seekForward() }) {
                            Icon(Icons.Default.Forward30, contentDescription = "Forward 30s", modifier = Modifier.size(48.dp))
                        }
                        IconButton(onClick = { viewModel.nextTrack() }) {
                            Icon(Icons.Default.SkipNext, contentDescription = "Next", modifier = Modifier.size(36.dp))
                        }
                        IconButton(onClick = { viewModel.cycleRepeatMode() }) {
                            Icon(
                                if (state.repeatMode == RepeatMode.ONE) Icons.Default.RepeatOne else Icons.Default.Repeat,
                                contentDescription = "Repeat",
                                tint = if (state.repeatMode != RepeatMode.OFF) MaterialTheme.colorScheme.primary
                                    else MaterialTheme.colorScheme.onSurfaceVariant,
                                modifier = Modifier.size(28.dp)
                            )
                        }
                    }

                    VolumeRow(volume = state.volume, viewModel)
                }
            }
            else -> {
                Box(modifier = Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                    Text("No track selected", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun ProgressSlider(state: PlayerUiState, viewModel: PlayerViewModel) {
    var dragFraction by remember { mutableStateOf<Float?>(null) }
    val fraction = dragFraction ?: (if (state.duration > 0) state.currentPosition.toFloat() / state.duration else 0f)
    val displayPosition = dragFraction?.let { (it * state.duration).toLong() } ?: state.currentPosition

    Column(modifier = Modifier.fillMaxWidth().padding(top = 16.dp)) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Text(formatDuration(displayPosition), style = MaterialTheme.typography.labelSmall)
            Text(formatDuration(state.duration), style = MaterialTheme.typography.labelSmall)
        }

        if (state.waveformState.isLoaded && state.waveformState.sampleCount > 0) {
            WaveformSeekbar(
                waveformSamples = state.waveformState.samples,
                currentPositionMs = state.currentPosition,
                durationMs = state.duration,
                onSeek = { viewModel.seekTo(it) }
            )
        } else {
            Slider(
                value = fraction.coerceIn(0f, 1f),
                onValueChange = { dragFraction = it },
                onValueChangeFinished = {
                    dragFraction?.let { viewModel.seekTo((it * state.duration).toLong()) }
                    dragFraction = null
                },
                modifier = Modifier.fillMaxWidth()
            )
        }
    }
}

@Composable
private fun VolumeRow(volume: Float, viewModel: PlayerViewModel) {
    var localVolume by remember { mutableStateOf(volume) }
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(Icons.Default.VolumeDown, contentDescription = "Volume down",
            modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
        Slider(
            value = localVolume,
            onValueChange = { localVolume = it; viewModel.setVolume(it) },
            modifier = Modifier.weight(1f)
        )
        Icon(Icons.Default.VolumeUp, contentDescription = "Volume up",
            modifier = Modifier.size(24.dp), tint = MaterialTheme.colorScheme.onSurfaceVariant)
    }
}

@Composable
private fun SleepTimerOption(
    minutes: Int,
    activeMinutes: Int,
    viewModel: PlayerViewModel,
    onSet: () -> Unit
) {
    TextButton(
        onClick = { viewModel.setSleepTimer(minutes); onSet() },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (activeMinutes == minutes) "✓ $minutes minutes" else "$minutes minutes")
    }
}

private fun formatDuration(ms: Long): String {
    val totalSec = ms / 1000
    val min = totalSec / 60
    val sec = totalSec % 60
    return "%d:%02d".format(min, sec)
}
