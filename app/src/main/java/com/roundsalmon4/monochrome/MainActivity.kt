package com.roundsalmon4.monochrome

import android.Manifest
import android.app.PictureInPictureParams
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.launch
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.core.app.ActivityCompat
import androidx.core.content.ContextCompat
import androidx.media3.common.Player
import com.roundsalmon4.monochrome.core.api.internal.AmazonMusicClient
import com.roundsalmon4.monochrome.core.api.internal.MonochromeSessionRefresher
import com.roundsalmon4.monochrome.core.datastore.PlayerPreferences
import com.roundsalmon4.monochrome.core.datastore.PreferencesUiState
import com.roundsalmon4.monochrome.player.PlayerEngineController
import com.roundsalmon4.monochrome.player.PlayerStateManager
import com.roundsalmon4.monochrome.ui.navigation.AppNavigation
import com.roundsalmon4.monochrome.ui.theme.MonochromeTheme
import dagger.hilt.android.AndroidEntryPoint
import javax.inject.Inject

@AndroidEntryPoint
class MainActivity : ComponentActivity() {

    @Inject
    lateinit var playerPreferences: PlayerPreferences

    @Inject
    lateinit var playerStateManager: PlayerStateManager

    @Inject
    lateinit var playerController: PlayerEngineController

    @Inject
    lateinit var amazonMusicClient: AmazonMusicClient

    @Inject
    lateinit var monochromeSessionRefresher: MonochromeSessionRefresher

    @Volatile
    private var pipEnabled = true

    // onStop() does not always follow onUserLeaveHint() (e.g. switching apps from
    // Overview), so a debounced PiP check is posted there too. The delay lets
    // transient stops (dialogs, overlays, config changes) cancel in onStart().
    private val pipHandler = Handler(Looper.getMainLooper())
    private val pipStopRunnable = Runnable { tryEnterPictureInPicture() }
    private val pipStopDelayMs = 300L

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        requestNotificationPermission()
        monochromeSessionRefresher.startAutoRefresh()
        lifecycleScope.launch {
            monochromeSessionRefresher.getValidToken()
        }
        lifecycleScope.launch {
            playerPreferences.uiState.collect { pipEnabled = it.pipEnabled }
        }
        lifecycleScope.launch {
            val saved = playerPreferences.getAmazonJwt()
            if (saved != null && System.currentTimeMillis() < saved.second) {
                amazonMusicClient.setJwt(saved.first, saved.second)
                android.util.Log.d("ChromePlayer", "Loaded Amazon JWT from preferences")
            }
        }
        lifecycle.addObserver(LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_DESTROY) {
                playerController.release()
            }
        })
        setContent {
            val prefs by playerPreferences.uiState.collectAsState(initial = PreferencesUiState())
            val systemDark = isSystemInDarkTheme()
            val darkTheme = when (prefs.themeMode) {
                "LIGHT" -> false
                "DARK" -> true
                else -> systemDark
            }

            MonochromeTheme(
                darkTheme = darkTheme,
                useAmoled = prefs.useAmoledTheme,
                primaryColor = prefs.primaryColor,
                secondaryColor = prefs.secondaryColor,
                colorSchemeMode = prefs.colorSchemeMode
            ) {
                AppNavigation(
                    playerStateManager = playerStateManager,
                    playerController = playerController,
                    playerPreferences = playerPreferences
                )
            }
        }
    }

    override fun onUserLeaveHint() {
        super.onUserLeaveHint()
        tryEnterPictureInPicture()
    }

    override fun onStop() {
        super.onStop()
        if (!isFinishing && !isChangingConfigurations) {
            pipHandler.postDelayed(pipStopRunnable, pipStopDelayMs)
        }
    }

    override fun onStart() {
        super.onStart()
        pipHandler.removeCallbacks(pipStopRunnable)
    }

    private fun tryEnterPictureInPicture() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        if (isInPictureInPictureMode || isFinishing || isChangingConfigurations) return
        // enterPictureInPictureMode throws IllegalStateException unless the
        // activity is resumed; the debounced onStop backstop can fire after that.
        if (!lifecycle.currentState.isAtLeast(Lifecycle.State.RESUMED)) return
        if (!playerStateManager.isPlayerScreenVisible) return
        if (!pipEnabled) return

        val player = playerController.exoPlayer
        // Enter PiP while playing OR while rebuffering (isPlaying is briefly
        // false during rebuffers, which made PiP intermittent before).
        val activelyPlaying = player.isPlaying || player.playbackState == Player.STATE_BUFFERING
        if (!activelyPlaying) return

        val videoWidth = player.videoSize.width
        val videoHeight = player.videoSize.height
        val aspectRatio = if (videoWidth > 0 && videoHeight > 0) {
            Rational(videoWidth, videoHeight)
        } else {
            Rational(16, 9)
        }
        val params = PictureInPictureParams.Builder()
            .setAspectRatio(aspectRatio)
            .build()
        runCatching { enterPictureInPictureMode(params) }
    }

    override fun onPictureInPictureModeChanged(isInPictureInPictureMode: Boolean, newConfig: Configuration) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        playerStateManager.isPlayerScreenVisible = isInPictureInPictureMode
    }

    private fun requestNotificationPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            if (ContextCompat.checkSelfPermission(this, Manifest.permission.POST_NOTIFICATIONS)
                != PackageManager.PERMISSION_GRANTED
            ) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(Manifest.permission.POST_NOTIFICATIONS),
                    NOTIFICATION_PERMISSION_CODE
                )
            }
        }
    }

    companion object {
        private const val NOTIFICATION_PERMISSION_CODE = 1001
    }
}
