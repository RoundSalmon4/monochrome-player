package com.roundsalmon4.monochrome.player.service

import android.app.Notification
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roundsalmon4.monochrome.MainActivity
import com.roundsalmon4.monochrome.player.PlayerEngineController

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        val player = playerController?.exoPlayer
        if (player != null) {
            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(sessionPendingIntent())
                .build()
        } else {
            startForeground(FOREGROUND_SERVICE_ID, buildFallbackNotification())
        }
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Must call super so MediaSessionService issues startForeground + the media notification.
        return super.onStartCommand(intent, flags, startId)
    }

    override fun onGetSession(controllerInfo: MediaSession.ControllerInfo): MediaSession? = mediaSession

    override fun onTaskRemoved(rootIntent: Intent?) {
        val player = mediaSession?.player
        if (player == null || !player.playWhenReady || player.mediaItemCount == 0) stopSelf()
    }

    override fun onDestroy() {
        mediaSession?.release()
        mediaSession = null
        super.onDestroy()
    }

    private fun sessionPendingIntent(): PendingIntent = PendingIntent.getActivity(
        this, 0, Intent(this, MainActivity::class.java),
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
    )

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "playback", "Playback", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Music playback" }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildFallbackNotification(): Notification =
        Notification.Builder(this, "playback")
            .setContentTitle("ChromePlayer")
            .setContentText("Preparing playback")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(sessionPendingIntent())
            .build()

    companion object {
        private const val FOREGROUND_SERVICE_ID = 1

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
