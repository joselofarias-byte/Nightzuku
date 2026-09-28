package moe.shizuku.manager.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.IBinder
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.MainActivity

/** Keeps the recovery watchdog running after boot without a visible Activity. */
class NightDogForegroundService : Service() {

    companion object {
        private const val CHANNEL_ID = "nightdog_persistence"
        private const val NOTIFICATION_ID = 1360

        fun start(context: Context) {
            if (!NightDogRecovery.isDesiredRunning(context)) return
            val intent = Intent(context, NightDogForegroundService::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                context.startForegroundService(intent)
            } else {
                context.startService(intent)
            }
        }

        fun stop(context: Context) {
            context.stopService(Intent(context, NightDogForegroundService::class.java))
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (!NightDogRecovery.isDesiredRunning(this)) {
            stopSelf(startId)
            return START_NOT_STICKY
        }
        try {
            val manager = getSystemService(NotificationManager::class.java)
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                manager.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Persistencia de Nightzuku", NotificationManager.IMPORTANCE_LOW)
                )
            }
            val open = PendingIntent.getActivity(
                this, 0, Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            )
            val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                Notification.Builder(this, CHANNEL_ID)
            } else {
                Notification.Builder(this)
            }
            startForeground(NOTIFICATION_ID, builder
                .setSmallIcon(android.R.drawable.stat_notify_sync)
                .setContentTitle("Nightzuku")
                .setContentText("Persistencia y recuperación del servicio activadas")
                .setContentIntent(open)
                .setOngoing(true)
                .build())
        } catch (error: RuntimeException) {
            Log.e(AppConstants.TAG, "NightDog foreground startup failed", error)
            stopSelf(startId)
            return START_NOT_STICKY
        }
        NightDogRecovery.start(this)
        return START_STICKY
    }
}
