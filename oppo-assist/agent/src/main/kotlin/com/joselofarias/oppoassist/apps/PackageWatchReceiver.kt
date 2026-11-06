package com.joselofarias.oppoassist.apps

import android.app.NotificationChannel
import android.app.NotificationManager
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import androidx.core.app.NotificationCompat
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.R
import com.joselofarias.oppoassist.rpc.AppsGateway

class PackageWatchReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent?) {
        if (intent?.action != Intent.ACTION_PACKAGE_ADDED) return
        if (intent.getBooleanExtra(Intent.EXTRA_REPLACING, false)) return
        val packageName = intent.data?.schemeSpecificPart ?: return
        val app = OppoAssistApp.from(context)
        val baseline = AppsGateway.baseline(app)
        val discouraged = AppsGateway.readDiscouraged(app)
        val installer = installerOf(context, packageName)
        val interesting = packageName in discouraged ||
            installer.isNullOrBlank() ||
            (baseline.isNotEmpty() && packageName !in baseline) ||
            baseline.isEmpty()
        if (!interesting) return
        val manager = context.getSystemService(NotificationManager::class.java)
        manager.createNotificationChannel(
            NotificationChannel(CHANNEL, context.getString(R.string.channel_apps), NotificationManager.IMPORTANCE_HIGH),
        )
        val notification = NotificationCompat.Builder(context, CHANNEL)
            .setSmallIcon(R.drawable.ic_notify)
            .setContentTitle(context.getString(R.string.app_name))
            .setContentText(context.getString(R.string.new_app, packageName))
            .setStyle(NotificationCompat.BigTextStyle().bigText(context.getString(R.string.new_app, packageName)))
            .setAutoCancel(true)
            .build()
        manager.notify(packageName.hashCode(), notification)
    }

    private fun installerOf(context: Context, packageName: String): String? = try {
        val manager = context.packageManager
        if (android.os.Build.VERSION.SDK_INT >= 30) {
            manager.getInstallSourceInfo(packageName).installingPackageName
        } else {
            @Suppress("DEPRECATION")
            manager.getInstallerPackageName(packageName)
        }
    } catch (_: PackageManager.NameNotFoundException) {
        null
    }

    companion object {
        private const val CHANNEL = "apps"
    }
}
