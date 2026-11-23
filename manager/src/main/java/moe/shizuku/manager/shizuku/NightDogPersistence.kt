package moe.shizuku.manager.shizuku

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ActivityNotFoundException
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.MainActivity
import moe.shizuku.manager.R
import moe.shizuku.manager.adb.AdbPairingTutorialActivity
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.WirelessDebuggingRestoreResult

/**
 * Abstracción del entorno del dispositivo para desacoplar la lógica de recuperación
 * de las APIs de Android y permitir pruebas deterministas JVM.
 */
internal interface NightDogSystemEnvironment {
    fun isWifiConnected(): Boolean
    fun hasLocalPersistentTcpEndpoint(): Boolean
    fun isAutoRestoreWirelessEnabled(): Boolean
    fun tryEnableWirelessDebugging(): WirelessDebuggingRestoreResult
    fun isIgnoringBatteryOptimizations(): Boolean?
    fun onServiceConfirmedRunning() {}
}

/**
 * Puerto para programación en segundo plano (JobScheduler) y notificaciones accionables.
 */
internal interface NightDogSchedulerPort {
    fun scheduleNetworkRecovery(delayMs: Long)
    fun scheduleWirelessSettingTrigger()
    fun schedulePeriodicHealthCheck()
    fun cancelRecoveryRetries()
    fun cancelAllJobs()
    fun notifyActionRequired(
        category: NightDogRecovery.FailureCategory,
        actionHint: NightDogRecovery.ActionHint,
        message: String
    )
    fun clearActionNotification()
}

internal object NoOpNightDogSchedulerPort : NightDogSchedulerPort {
    override fun scheduleNetworkRecovery(delayMs: Long) = Unit
    override fun scheduleWirelessSettingTrigger() = Unit
    override fun schedulePeriodicHealthCheck() = Unit
    override fun cancelRecoveryRetries() = Unit
    override fun cancelAllJobs() = Unit
    override fun notifyActionRequired(
        category: NightDogRecovery.FailureCategory,
        actionHint: NightDogRecovery.ActionHint,
        message: String
    ) = Unit
    override fun clearActionNotification() = Unit
}

internal object NightDogSettingsShortcuts {

    fun developerWirelessDebuggingIntent(): Intent =
        Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra(":settings:fragment_args_key", "toggle_adb_wireless")
        }

    fun wifiSettingsIntent(): Intent =
        Intent(Settings.ACTION_WIFI_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
        }

    fun pairingActivityIntent(context: Context): Intent =
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            Intent(context, AdbPairingTutorialActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        } else {
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            }
        }

    fun batterySettingsIntent(context: Context): Intent =
        Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    fun appDetailsIntent(context: Context): Intent =
        Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS).apply {
            data = Uri.fromParts("package", context.packageName, null)
            flags = Intent.FLAG_ACTIVITY_NEW_TASK
        }

    fun launch(context: Context, intent: Intent, fallback: Intent? = null): Boolean {
        return try {
            context.startActivity(intent)
            true
        } catch (_: ActivityNotFoundException) {
            if (fallback != null) {
                runCatching { context.startActivity(fallback) }.isSuccess
            } else {
                false
            }
        } catch (_: Throwable) {
            false
        }
    }
}

internal object NightDogJobScheduler {

    private const val TAG = "NightDogJobScheduler"
    const val JOB_ID_NETWORK_RECOVERY = 7101
    const val JOB_ID_WIRELESS_SETTING_TRIGGER = 7102
    const val JOB_ID_PERIODIC_HEALTH = 7103

    private const val MIN_NETWORK_RECOVERY_LATENCY_MS = 5_000L
    private const val MAX_NETWORK_RECOVERY_LATENCY_MS = 60_000L
    private const val PERIODIC_HEALTH_INTERVAL_MS = 15 * 60 * 1_000L

    fun scheduleNetworkRecovery(context: Context, delayMs: Long) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val clampedDelay = delayMs.coerceIn(MIN_NETWORK_RECOVERY_LATENCY_MS, MAX_NETWORK_RECOVERY_LATENCY_MS)
        val component = ComponentName(context, NightDogRecoveryJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID_NETWORK_RECOVERY, component)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_UNMETERED)
            .setMinimumLatency(clampedDelay)
            .setOverrideDeadline(clampedDelay + 120_000L)
            .setPersisted(true)
        runCatching { scheduler.schedule(builder.build()) }
            .onSuccess { result ->
                NightDogTrace.record(context, "JOB_SCHEDULE id=$JOB_ID_NETWORK_RECOVERY result=$result minMs=$clampedDelay deadlineMs=${clampedDelay + 120_000L} network=unmetered")
            }.onFailure {
                NightDogTrace.record(context, "JOB_SCHEDULE_ERROR id=$JOB_ID_NETWORK_RECOVERY")
                Log.w(TAG, "No se pudo programar JOB_ID_NETWORK_RECOVERY", it)
            }
    }

    fun scheduleWirelessSettingTrigger(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        val uri = Settings.Global.getUriFor(AdbTransportResolver.ADB_WIFI_ENABLED) ?: return
        val component = ComponentName(context, NightDogRecoveryJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID_WIRELESS_SETTING_TRIGGER, component)
            .addTriggerContentUri(JobInfo.TriggerContentUri(uri, 0))
            .setTriggerContentUpdateDelay(500L)
            .setTriggerContentMaxDelay(3_000L)
        runCatching { scheduler.schedule(builder.build()) }
            .onSuccess { result ->
                NightDogTrace.record(context, "JOB_SCHEDULE id=$JOB_ID_WIRELESS_SETTING_TRIGGER result=$result trigger=adb_wifi_enabled")
            }.onFailure {
                NightDogTrace.record(context, "JOB_SCHEDULE_ERROR id=$JOB_ID_WIRELESS_SETTING_TRIGGER")
                Log.w(TAG, "No se pudo programar JOB_ID_WIRELESS_SETTING_TRIGGER", it)
            }
    }

    fun schedulePeriodicHealthCheck(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        if (scheduler.getPendingJob(JOB_ID_PERIODIC_HEALTH) != null) return
        val component = ComponentName(context, NightDogRecoveryJobService::class.java)
        val builder = JobInfo.Builder(JOB_ID_PERIODIC_HEALTH, component)
            .setPeriodic(PERIODIC_HEALTH_INTERVAL_MS)
            .setRequiredNetworkType(JobInfo.NETWORK_TYPE_NONE)
            .setPersisted(true)
        runCatching { scheduler.schedule(builder.build()) }
            .onSuccess { result ->
                NightDogTrace.record(context, "JOB_SCHEDULE id=$JOB_ID_PERIODIC_HEALTH result=$result intervalMs=$PERIODIC_HEALTH_INTERVAL_MS")
            }.onFailure {
                NightDogTrace.record(context, "JOB_SCHEDULE_ERROR id=$JOB_ID_PERIODIC_HEALTH")
                Log.w(TAG, "No se pudo programar JOB_ID_PERIODIC_HEALTH", it)
            }
    }

    fun cancelRecoveryRetries(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(JOB_ID_NETWORK_RECOVERY) }
        runCatching { scheduler.cancel(JOB_ID_WIRELESS_SETTING_TRIGGER) }
    }

    fun cancelAll(context: Context) {
        val scheduler = context.getSystemService(JobScheduler::class.java) ?: return
        runCatching { scheduler.cancel(JOB_ID_NETWORK_RECOVERY) }
        runCatching { scheduler.cancel(JOB_ID_WIRELESS_SETTING_TRIGGER) }
        runCatching { scheduler.cancel(JOB_ID_PERIODIC_HEALTH) }
    }
}

internal object NightDogNotificationHelper {

    private const val TAG = "NightDogNotification"
    private const val CHANNEL_ID = "nightdog_recovery_action"
    private const val NOTIFICATION_ID = 7109
    private const val ACTION_REQUEST_CODE = 7110
    private const val OPEN_APP_REQUEST_CODE = 7111

    @Volatile
    private var lastNotifiedKey: String? = null

    fun notifyActionRequired(
        context: Context,
        category: NightDogRecovery.FailureCategory,
        actionHint: NightDogRecovery.ActionHint,
        message: String
    ) {
        if (actionHint == NightDogRecovery.ActionHint.NONE) return
        val key = "${category.name}:${actionHint.name}"
        if (lastNotifiedKey == key) return

        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        if (!nm.areNotificationsEnabled()) return

        ensureChannel(nm)

        val (title, actionLabel, targetIntent) = when (actionHint) {
            NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING -> Triple(
                "Depuración inalámbrica requerida",
                "Abrir depuración inalámbrica",
                NightDogSettingsShortcuts.developerWirelessDebuggingIntent()
            )
            NightDogRecovery.ActionHint.OPEN_PAIRING -> Triple(
                "Reemparejamiento ADB requerido",
                "Emparejar Nightzuku",
                NightDogSettingsShortcuts.pairingActivityIntent(context)
            )
            NightDogRecovery.ActionHint.CONNECT_WIFI -> Triple(
                "Wi-Fi requerido para recuperar Nightzuku",
                "Abrir Wi-Fi",
                NightDogSettingsShortcuts.wifiSettingsIntent()
            )
            NightDogRecovery.ActionHint.OPEN_BATTERY_SETTINGS -> Triple(
                "Restricción de batería detectada",
                "Ajustes de batería",
                NightDogSettingsShortcuts.batterySettingsIntent(context)
            )
            NightDogRecovery.ActionHint.NONE -> return
        }

        val pendingFlags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        } else {
            PendingIntent.FLAG_UPDATE_CURRENT
        }

        val actionPendingIntent = PendingIntent.getActivity(
            context,
            ACTION_REQUEST_CODE,
            targetIntent,
            pendingFlags
        )

        val contentPendingIntent = PendingIntent.getActivity(
            context,
            OPEN_APP_REQUEST_CODE,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            pendingFlags
        )

        val builder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            Notification.Builder(context, CHANNEL_ID)
        } else {
            @Suppress("DEPRECATION")
            Notification.Builder(context)
        }

        val notification = builder
            .setSmallIcon(R.drawable.ic_system_icon)
            .setColor(context.getColor(R.color.notification))
            .setContentTitle(title)
            .setContentText(message)
            .setStyle(Notification.BigTextStyle().bigText(message))
            .setContentIntent(contentPendingIntent)
            .setAutoCancel(true)
            .setOnlyAlertOnce(true)
            .addAction(
                Notification.Action.Builder(null, actionLabel, actionPendingIntent).build()
            )
            .build()

        runCatching {
            nm.notify(NOTIFICATION_ID, notification)
            lastNotifiedKey = key
        }.onFailure { Log.w(TAG, "No se pudo publicar notificación de acción requerida", it) }
    }

    fun clear(context: Context) {
        lastNotifiedKey = null
        val nm = context.getSystemService(NotificationManager::class.java) ?: return
        runCatching { nm.cancel(NOTIFICATION_ID) }
    }

    private fun ensureChannel(nm: NotificationManager) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            "Recuperación NightDog",
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = "Avisos cuando Android o MagicOS requieren activar depuración inalámbrica o reemparejar ADB"
            setSound(null, null)
            enableVibration(false)
            setShowBadge(false)
        }
        nm.createNotificationChannel(channel)
    }
}

internal class AndroidNightDogSchedulerPort(
    private val contextProvider: () -> Context?
) : NightDogSchedulerPort {
    override fun scheduleNetworkRecovery(delayMs: Long) {
        contextProvider()?.let { NightDogJobScheduler.scheduleNetworkRecovery(it, delayMs) }
    }

    override fun scheduleWirelessSettingTrigger() {
        contextProvider()?.let { NightDogJobScheduler.scheduleWirelessSettingTrigger(it) }
    }

    override fun schedulePeriodicHealthCheck() {
        contextProvider()?.let { NightDogJobScheduler.schedulePeriodicHealthCheck(it) }
    }

    override fun cancelRecoveryRetries() {
        contextProvider()?.let { NightDogJobScheduler.cancelRecoveryRetries(it) }
    }

    override fun cancelAllJobs() {
        contextProvider()?.let { NightDogJobScheduler.cancelAll(it) }
    }

    override fun notifyActionRequired(
        category: NightDogRecovery.FailureCategory,
        actionHint: NightDogRecovery.ActionHint,
        message: String
    ) {
        contextProvider()?.let {
            NightDogNotificationHelper.notifyActionRequired(it, category, actionHint, message)
        }
    }

    override fun clearActionNotification() {
        contextProvider()?.let { NightDogNotificationHelper.clear(it) }
    }
}
