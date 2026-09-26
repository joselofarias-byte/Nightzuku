package moe.shizuku.manager.startup

import android.content.Context
import android.util.Log
import androidx.work.BackoffPolicy
import androidx.work.CoroutineWorker
import androidx.work.ExistingWorkPolicy
import androidx.work.OneTimeWorkRequestBuilder
import androidx.work.WorkManager
import androidx.work.WorkerParameters
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbEndpoint
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbLocalWirelessDiscovery
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.persistence.DeveloperOptionsController
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

/**
 * Reliable post-boot starter.
 *
 * The receiver only schedules this worker. The worker stays alive long enough to
 * recover the authenticated ADB transport and start the Nightzuku server without
 * opening UI. This mirrors Stellar's reliable Receiver -> WorkManager lifecycle,
 * while keeping Nightzuku's validated HONOR 200 recovery policy.
 */
class BootStartWorker(
    appContext: Context,
    params: WorkerParameters
) : CoroutineWorker(appContext, params) {

    companion object {
        private const val UNIQUE_WORK_NAME = "nightzuku_boot_start"

        fun enqueue(context: Context) {
            val request = OneTimeWorkRequestBuilder<BootStartWorker>()
                .setBackoffCriteria(BackoffPolicy.LINEAR, 15, TimeUnit.SECONDS)
                .build()

            WorkManager.getInstance(context).enqueueUniqueWork(
                UNIQUE_WORK_NAME,
                ExistingWorkPolicy.REPLACE,
                request
            )
        }
    }

    override suspend fun doWork(): Result {
        val context = applicationContext

        if (context.packageManager.isSafeMode) {
            Log.w(AppConstants.TAG, "Boot worker skipped in Safe Mode")
            return Result.success()
        }

        if (UserHandleCompat.myUserId() > 0) return Result.success()
        if (!NightDogRecovery.isDesiredRunning(context)) return Result.success()
        if (Shizuku.pingBinder()) return Result.success()

        return when (ShizukuSettings.getLastLaunchMode()) {
            LaunchMethod.ROOT -> startRoot()
            LaunchMethod.ADB -> startAdb()
            else -> {
                Log.i(AppConstants.TAG, "Boot worker: no previous launch mode to restore")
                Result.success()
            }
        }
    }

    private suspend fun startRoot(): Result = withContext(Dispatchers.IO) {
        return@withContext runCatching {
            if (!Shell.getShell().isRoot) {
                Shell.getCachedShell()?.close()
                return@runCatching false
            }

            val result = Shell.cmd(Starter.internalCommand).exec()
            result.code == 0 && waitForBinder()
        }.fold(
            onSuccess = { started ->
                if (started) {
                    ShizukuSettings.setLastLaunchMode(LaunchMethod.ROOT)
                    Result.success()
                } else {
                    Result.retry()
                }
            },
            onFailure = { error ->
                Log.w(AppConstants.TAG, "Root start on boot failed", error)
                Result.retry()
            }
        )
    }

    private suspend fun startAdb(): Result {
        if (tryKnownEndpoints(includeDynamicScan = false)) {
            return completeAdbStart()
        }

        val debugState = DeveloperOptionsController.snapshot(applicationContext)
        if (!debugState.writeSecureSettingsGranted) {
            Log.w(AppConstants.TAG, "Boot worker: WRITE_SECURE_SETTINGS is not available")
            ShizukuSettings.setAdbReactivationRequired(true)
            return Result.retry()
        }

        // First restore only the internal ADB transport. Developer options itself
        // remains visually OFF, matching the path validated on the HONOR 200.
        val adbOnly = DeveloperOptionsController.enableAdbTransportForRecovery(
            applicationContext,
            enableWireless = false
        )

        if (adbOnly.success) {
            delay(500L)
            if (tryKnownEndpoints(includeDynamicScan = true)) {
                return completeAdbStart()
            }
        } else {
            Log.w(AppConstants.TAG, "Boot worker: internal ADB recovery failed: ${adbOnly.detail}")
        }

        // Last resort: briefly enable Wireless debugging so Android publishes a
        // fresh authenticated endpoint. It is switched back off after recovery.
        val wireless = DeveloperOptionsController.enableAdbTransportForRecovery(
            applicationContext,
            enableWireless = true
        )

        if (wireless.success) {
            discoverMdnsWindow()
            if (tryKnownEndpoints(includeDynamicScan = true)) {
                return completeAdbStart()
            }
        } else {
            Log.w(AppConstants.TAG, "Boot worker: wireless ADB recovery failed: ${wireless.detail}")
        }

        DeveloperOptionsController.restoreAdbTransportAfterRecovery(applicationContext)
        ShizukuSettings.setAdbReactivationRequired(true)
        Log.w(AppConstants.TAG, "Boot worker: service not recovered; WorkManager will retry")
        return Result.retry()
    }

    private suspend fun completeAdbStart(): Result {
        ShizukuSettings.setLastLaunchMode(LaunchMethod.ADB)
        ShizukuSettings.setAdbReactivationRequired(false)
        DeveloperOptionsController.restoreAdbTransportAfterRecovery(applicationContext)
        Log.i(AppConstants.TAG, "Nightzuku service restored automatically after boot")
        return Result.success()
    }

    private suspend fun tryKnownEndpoints(includeDynamicScan: Boolean): Boolean =
        withContext(Dispatchers.IO) {
            if (Shizuku.pingBinder()) return@withContext true

            val endpoints = linkedSetOf<AdbEndpoint>().apply {
                AdbTransportResolver.persistentTcpEndpoint()?.let(::add)
                AdbTransportResolver.systemAdbTcpEndpoint()?.let(::add)
                AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)?.let(::add)
                AdbLocalWirelessDiscovery.lastKnownEndpoint()?.let(::add)
                if (includeDynamicScan) {
                    AdbLocalWirelessDiscovery.discover()?.let(::add)
                }
            }

            for (endpoint in endpoints) {
                if (tryAuthenticatedStart(endpoint) && waitForBinder()) {
                    return@withContext true
                }
            }

            Shizuku.pingBinder()
        }

    private fun tryAuthenticatedStart(endpoint: AdbEndpoint): Boolean {
        return runCatching {
            val key = AdbKey(
                PreferenceAdbKeyStore(ShizukuSettings.getPreferences()),
                "shizuku"
            )
            AdbClient(endpoint.host, endpoint.port, key).use { client ->
                client.connect()
                client.shellCommand(Starter.internalCommand, null)
            }
            true
        }.onFailure { error ->
            Log.w(
                AppConstants.TAG,
                "Boot worker ADB start failed at ${endpoint.host}:${endpoint.port}",
                error
            )
        }.getOrDefault(false)
    }

    private suspend fun waitForBinder(): Boolean {
        repeat(20) {
            if (Shizuku.pingBinder()) return true
            delay(250L)
        }
        return Shizuku.pingBinder()
    }

    private suspend fun discoverMdnsWindow() = withContext(Dispatchers.IO) {
        val latch = CountDownLatch(1)
        val mdns = AdbMdns(applicationContext, AdbMdns.TLS_CONNECT) { port ->
            if (port > 0) latch.countDown()
        }
        try {
            mdns.start()
            latch.await(4, TimeUnit.SECONDS)
        } finally {
            mdns.stop()
        }
    }
}
