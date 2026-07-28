package com.roundsalmon4.monochrome.ui.navigation

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.LibraryMusic
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material3.Icon
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.toRoute
import com.roundsalmon4.monochrome.core.api.model.Track
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import com.roundsalmon4.monochrome.core.datastore.PreferencesUiState
import com.roundsalmon4.monochrome.player.PlayerEngineController
import com.roundsalmon4.monochrome.player.PlayerStateManager
import com.roundsalmon4.monochrome.player.service.PlaybackService
import com.roundsalmon4.monochrome.ui.album.AlbumDetailScreen
import com.roundsalmon4.monochrome.ui.artist.ArtistDetailScreen
import com.roundsalmon4.monochrome.ui.components.MiniPlayer
import com.roundsalmon4.monochrome.ui.home.HomeScreen
import com.roundsalmon4.monochrome.ui.library.LibraryScreen
import com.roundsalmon4.monochrome.ui.library.playlist.PlaylistDetailScreen
import com.roundsalmon4.monochrome.ui.player.PlayerScreen
import com.roundsalmon4.monochrome.ui.search.SearchScreen
import com.roundsalmon4.monochrome.ui.settings.SettingsScreen

data class BottomNavItem(val label: String, val icon: ImageVector, val route: Route)

val bottomNavItems = listOf(
    BottomNavItem("Home", Icons.Default.Home, Route.Home),
    BottomNavItem("Search", Icons.Default.Search, Route.Search),
    BottomNavItem("Library", Icons.Default.LibraryMusic, Route.Library),
    BottomNavItem("Settings", Icons.Default.Settings, Route.Settings)
)

@Composable
fun AppNavigation(
    playerStateManager: PlayerStateManager,
    playerController: PlayerEngineController,
    playerPreferences: PlayerPreferences
) {
    val navController = rememberNavController()
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination
    val context = LocalContext.current

    val showBottomBar = bottomNavItems.any { item ->
        currentDestination?.hierarchy?.any { it.route == item.route::class.qualifiedName } == true
    }

    val isOnPlayerScreen = currentDestination?.hierarchy?.any {
        it.route == Route.Player::class.qualifiedName
    } == true

    val miniPlayerState by playerStateManager.miniPlayerState.collectAsState()
    val prefs by playerPreferences.uiState.collectAsState(initial = PreferencesUiState())

    val playTracks: (List<Track>, Int) -> Unit = { tracks, index ->
        playerStateManager.setQueue(tracks, index)
        navController.navigate(Route.Player)
    }

    Scaffold(
        bottomBar = {
            if (showBottomBar) {
                NavigationBar {
                    bottomNavItems.forEach { item ->
                        NavigationBarItem(
                            icon = { Icon(item.icon, contentDescription = item.label) },
                            label = { Text(item.label) },
                            selected = currentDestination?.hierarchy?.any {
                                it.route == item.route::class.qualifiedName
                            } == true,
                            onClick = {
                                navController.navigate(item.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            }
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        Column(modifier = Modifier.padding(innerPadding)) {
            MiniPlayer(
                state = miniPlayerState,
                isVisible = showBottomBar && !isOnPlayerScreen && prefs.showMiniPlayer,
                onPlayPause = {
                    playerController.togglePlayPause()
                    playerStateManager.updatePlaybackState(
                        isPlaying = !miniPlayerState.isPlaying,
                        currentPosition = playerController.exoPlayer.currentPosition,
                        duration = playerController.exoPlayer.duration,
                        bufferedPosition = playerController.exoPlayer.bufferedPosition
                    )
                },
                onRewind = { playerController.seekBackward() },
                onForward = { playerController.seekForward() },
                onClose = {
                    playerController.stop()
                    playerStateManager.clear()
                    PlaybackService.stop(context)
                },
                onTap = {
                    if (miniPlayerState.trackId.isNotEmpty()) {
                        navController.navigate(Route.Player)
                    }
                }
            )

            NavHost(
                navController = navController,
                startDestination = Route.Home
            ) {
                composable<Route.Home> {
                    HomeScreen(
                        onAlbumClick = { albumId ->
                            navController.navigate(Route.Album(albumId))
                        },
                        onArtistClick = { artistId ->
                            navController.navigate(Route.Artist(artistId))
                        }
                    )
                }

                composable<Route.Player> {
                    PlayerScreen(
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable<Route.Search> {
                    SearchScreen(
                        onAlbumClick = { albumId ->
                            navController.navigate(Route.Album(albumId))
                        },
                        onArtistClick = { artistId ->
                            navController.navigate(Route.Artist(artistId))
                        }
                    )
                }

                composable<Route.Album> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.Album>()
                    AlbumDetailScreen(
                        albumId = route.albumId,
                        onBackClick = { navController.popBackStack() },
                        onTrackClick = playTracks
                    )
                }

                composable<Route.Artist> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.Artist>()
                    ArtistDetailScreen(
                        artistId = route.artistId,
                        onBackClick = { navController.popBackStack() },
                        onAlbumClick = { albumId ->
                            navController.navigate(Route.Album(albumId))
                        }
                    )
                }

                composable<Route.Library> {
                    LibraryScreen(
                        onTrackClick = {
                            navController.navigate(Route.Player)
                        },
                        onPlaylistClick = { playlistId ->
                            navController.navigate(Route.PlaylistDetail(playlistId))
                        }
                    )
                }

                composable<Route.PlaylistDetail> { backStackEntry ->
                    val route = backStackEntry.toRoute<Route.PlaylistDetail>()
                    PlaylistDetailScreen(
                        playlistId = route.playlistId,
                        onTrackClick = { tracks, index ->
                            playerStateManager.setQueue(tracks, index)
                            navController.navigate(Route.Player)
                        },
                        onBackClick = { navController.popBackStack() }
                    )
                }

                composable<Route.Settings> {
                    SettingsScreen()
                }
            }
        }
    }
}
