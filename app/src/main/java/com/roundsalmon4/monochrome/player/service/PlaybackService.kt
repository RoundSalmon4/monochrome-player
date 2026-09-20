/*
 * Copyright (c) 2026 RoundSalmon4
 *
 * Derived from the PhoneTube playback service (https://github.com/RoundSalmon4/PhoneTube),
 * MIT-licensed (c) 2020-present yuliskov, (c) 2025-present RoundSalmon4. MIT license text
 * is reproduced in the repository LICENSE file.
 */
package com.roundsalmon4.monochrome.player.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import androidx.media3.common.MediaMetadata
import androidx.media3.common.Player
import androidx.media3.common.util.UnstableApi
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roundsalmon4.monochrome.MainActivity
import com.roundsalmon4.monochrome.player.PlayerEngineController
import dagger.hilt.android.AndroidEntryPoint

/**
 * Foreground playback service. Follows the PhoneTube implementation: it calls
 * startForeground() immediately in onCreate() and on every start/refresh, which
 * satisfies the "startForegroundService must call startForeground within 5s"
 * rule and avoids ForegroundServiceDidNotStartInTimeException crashes.
 */
@UnstableApi
@AndroidEntryPoint
class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    private val playerListener = object : Player.Listener {
        override fun onIsPlayingChanged(isPlaying: Boolean) { refreshNotification() }
        override fun onPlaybackStateChanged(playbackState: Int) { refreshNotification() }
        override fun onMediaMetadataChanged(mediaMetadata: MediaMetadata) { refreshNotification() }
    }

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        // Guarantee the foreground is started in time (5s rule).
        startForegroundCompat(buildPlaceholderNotification())
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = playerController?.exoPlayer ?: return START_NOT_STICKY

        when (intent?.action) {
            ACTION_PLAY_PAUSE -> player.playWhenReady = !player.playWhenReady
            ACTION_PREVIOUS -> {
                if (player.currentPosition > 3000L && player.playbackState == Player.STATE_READY) {
                    player.seekTo(0)
                } else if (player.hasPreviousMediaItem()) {
                    player.seekToPrevious()
                }
            }
            ACTION_NEXT -> {
                if (player.hasNextMediaItem()) {
                    player.seekToNext()
                } else {
                    player.stop()
                    player.clearMediaItems()
                    stopSelf()
                }
            }
        }

        if (mediaSession == null) {
            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(sessionPendingIntent())
                .build()
            player.addListener(playerListener)
        }

        startForegroundCompat(buildNotification(player))
        return START_NOT_STICKY
    }

    private fun refreshNotification() {
        val player = playerController?.exoPlayer ?: return
        startForegroundCompat(buildNotification(player))
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        playerController?.exoPlayer?.let { player ->
            player.stop()
            player.clearMediaItems()
        }
        playerController = null
        stopSelf()
    }

    override fun onDestroy() {
        playerController?.exoPlayer?.removeListener(playerListener)
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun startForegroundCompat(notification: Notification) {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIFICATION_ID, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK)
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun sessionPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun buildNotification(player: Player): Notification {
        val isPlaying = player.isPlaying
        val title = player.mediaMetadata.title?.toString() ?: "ChromePlayer"
        val artist = player.mediaMetadata.artist?.toString() ?: "Playing"

        val playPauseIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PLAY_PAUSE)
        val previousIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_PREVIOUS)
        val nextIntent = Intent(this, PlaybackService::class.java).setAction(ACTION_NEXT)

        fun pending(intent: Intent, requestCode: Int): PendingIntent = PendingIntent.getService(
            this, requestCode, intent, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val playPauseAction = Notification.Action.Builder(
            if (isPlaying) android.R.drawable.ic_media_pause else android.R.drawable.ic_media_play,
            if (isPlaying) "Pause" else "Play",
            pending(playPauseIntent, 1)
        ).build()

        return Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(artist)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(sessionPendingIntent())
            .setOngoing(true)
            .setShowWhen(false)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_previous, "Previous", pending(previousIntent, 2)).build())
            .addAction(playPauseAction)
            .addAction(Notification.Action.Builder(android.R.drawable.ic_media_next, "Next", pending(nextIntent, 3)).build())
            .build()
    }

    private fun buildPlaceholderNotification(): Notification =
        Notification.Builder(this, NOTIFICATION_CHANNEL_ID)
            .setContentTitle("ChromePlayer")
            .setContentText("Starting playback...")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(sessionPendingIntent())
            .setOngoing(true)
            .build()

    private fun createNotificationChannel() {
        val channel = NotificationChannel(
            NOTIFICATION_CHANNEL_ID,
            "Playback",
            NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Music playback" }
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
    }

    companion object {
        private const val NOTIFICATION_CHANNEL_ID = "playback"
        private const val NOTIFICATION_ID = 1
        private const val ACTION_PLAY_PAUSE = "com.roundsalmon4.monochrome.action.PLAY_PAUSE"
        private const val ACTION_PREVIOUS = "com.roundsalmon4.monochrome.action.PREVIOUS"
        private const val ACTION_NEXT = "com.roundsalmon4.monochrome.action.NEXT"

        @Volatile
        var playerController: PlayerEngineController? = null

        fun start(controller: PlayerEngineController, context: Context) {
            playerController = controller
            context.startForegroundService(Intent(context, PlaybackService::class.java))
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, PlaybackService::class.java))
            playerController = null
        }
    }
}