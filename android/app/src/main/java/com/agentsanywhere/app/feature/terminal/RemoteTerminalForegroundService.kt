package com.agentsanywhere.app.feature.terminal

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import com.agentsanywhere.app.R

class RemoteTerminalForegroundService : Service() {
    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startTerminalForeground()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        // Every delivered start must satisfy Android's foreground deadline,
        // including a stop queued while the service was still being created.
        startTerminalForeground()
        if (intent?.action == ACTION_STOP) {
            stopForeground(STOP_FOREGROUND_REMOVE)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        return START_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopForeground(STOP_FOREGROUND_REMOVE)
        super.onDestroy()
    }

    private fun startTerminalForeground() {
        val notification = buildNotification()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(
                NOTIFICATION_ID,
                notification,
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC,
            )
        } else {
            startForeground(NOTIFICATION_ID, notification)
        }
    }

    private fun createNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Active session",
            NotificationManager.IMPORTANCE_LOW,
        ).apply {
            description = "Keeps the active session connected."
            setShowBadge(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        return Notification.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_launcher_foreground)
            .setContentTitle("Session active")
            .setContentText("Keeping the active session connected.")
            .setCategory(Notification.CATEGORY_SERVICE)
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .build()
    }

    companion object {
        private const val ACTION_STOP = "com.agentsanywhere.app.STOP_REMOTE_TERMINAL"
        private const val CHANNEL_ID = "remote_terminal"
        private const val NOTIFICATION_ID = 2001

        fun start(context: Context) {
            val intent = Intent(context, RemoteTerminalForegroundService::class.java)
            context.startForegroundService(intent)
        }

        fun stop(context: Context) {
            // Queue shutdown after pending starts rather than cancelling service
            // creation before it has a chance to call startForeground().
            context.startService(Intent(context, RemoteTerminalForegroundService::class.java).setAction(ACTION_STOP))
        }
    }
}
