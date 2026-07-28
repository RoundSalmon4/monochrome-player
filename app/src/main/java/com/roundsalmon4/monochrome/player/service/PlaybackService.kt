package com.roundsalmon4.monochrome.player.service

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import androidx.media3.session.MediaSession
import androidx.media3.session.MediaSessionService
import com.roundsalmon4.monochrome.MainActivity
import com.roundsalmon4.monochrome.player.PlayerEngineController

class PlaybackService : MediaSessionService() {

    private var mediaSession: MediaSession? = null

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(
            FOREGROUND_SERVICE_ID,
            buildNotification(),
            ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PLAYBACK
        )
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        val player = playerController?.exoPlayer ?: return START_NOT_STICKY
        if (mediaSession == null) {
            val pendingIntent = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            mediaSession = MediaSession.Builder(this, player)
                .setSessionActivity(pendingIntent)
                .build()
        }
        return START_NOT_STICKY
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

    private fun createNotificationChannel() {
        val channel = android.app.NotificationChannel(
            "playback", "Playback", android.app.NotificationManager.IMPORTANCE_LOW
        ).apply { description = "Music playback" }
        val manager = getSystemService(android.app.NotificationManager::class.java)
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification() = android.app.Notification.Builder(this, "playback")
        .setContentTitle("ChromePlayer")
        .setContentText("Playing music")
        .setSmallIcon(android.R.drawable.ic_media_play)
        .setContentIntent(PendingIntent.getActivity(this, 0, Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE))
        .setOngoing(true)
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
