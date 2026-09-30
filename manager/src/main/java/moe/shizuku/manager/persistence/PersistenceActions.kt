package moe.shizuku.manager.persistence

import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import android.os.SystemClock
import kotlinx.coroutines.delay
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRecoveryTestController
import moe.shizuku.manager.adb.AdbTcpController
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.dhizuku.DhizukuDeviceOwnerBridge
import rikka.shizuku.Shizuku

data class RecoveryTestReport(
    val success: Boolean,
    val killedPid: Int?,
    val recoveredPid: Int?,
    val elapsedSeconds: Int?,
    val lastStage: String
)

object PersistenceActions {

    data class VpnBootstrapStatus(
        val supported: Boolean,
        val enabledForNightzuku: Boolean,
        val configuredPackage: String?,
        val message: String
    )

    suspend fun recoverNow(context: Context): DeveloperOptionsController.Result? {
        val before = DeveloperOptionsController.snapshot(context)
        val debugResult = if (DeveloperOptionsController.shouldEnableForManualRecovery(before)) {
            DeveloperOptionsController.enableForRecovery(context)
        } else {
            null
        }

        if (debugResult == null || debugResult.success) {
            // Give Android a short settle window after re-enabling adbd-related
            // global switches before asking NightDog to rediscover transports.
            if (debugResult?.success == true) delay(1_000L)
            NightDogRecovery.requestImmediateRecovery(context)
        }

        return debugResult
    }

    fun setDesiredRunning(context: Context, desired: Boolean) {
        if (desired) {
            NightDogRecovery.requestImmediateRecovery(context)
        } else {
            NightDogRecovery.prepareForManualStop(context)
        }
    }

    suspend fun enableLocalTcp(): AdbTcpController.Result {
        val host = ShizukuSettings.getAdbTcpHost().trim().ifBlank { DEFAULT_HOST }
        val port = ShizukuSettings.getAdbTcpPort().takeIf { it in 1..65535 } ?: DEFAULT_PORT
        val result = AdbTcpController.enable(host, port)
        if (result.success) {
            ShizukuSettings.setAdbReactivationRequired(false)
        }
        return result
    }

    suspend fun testLocalTcp(): AdbTcpController.Result {
        return AdbTcpController.testStored()
    }

    suspend fun disableLocalTcp(): AdbTcpController.Result {
        return AdbTcpController.disable()
    }

    suspend fun classifyStoredTcp(): TcpHealth {
        return AdbTcpController.classifyStored()
    }

    suspend fun testRecovery(context: Context, timeoutSeconds: Int = 30): RecoveryTestReport {
        check(Shizuku.pingBinder()) { "service_not_running" }
        NightDogRecovery.requestImmediateRecovery(context)
        check(NightDogRecovery.snapshot.value.desiredRunning) { "desired_running_not_set" }

        val killed = AdbRecoveryTestController.killServerProcess()
        check(killed.success) { killed.message }
        val killedPid = killed.pid

        for (second in 1..timeoutSeconds) {
            delay(1_000L)
            val snapshot = NightDogRecovery.snapshot.value
            val binderAlive = runCatching { Shizuku.pingBinder() }.getOrDefault(false)
            val newPid = snapshot.serverPid
            val pidChanged = killedPid == null || (newPid != null && newPid != killedPid)
            if (binderAlive && pidChanged) {
                return RecoveryTestReport(
                    success = true,
                    killedPid = killedPid,
                    recoveredPid = newPid,
                    elapsedSeconds = second,
                    lastStage = snapshot.stage.name
                )
            }
        }

        val snapshot = NightDogRecovery.snapshot.value
        return RecoveryTestReport(
            success = false,
            killedPid = killedPid,
            recoveredPid = snapshot.serverPid,
            elapsedSeconds = timeoutSeconds,
            lastStage = snapshot.stage.name
        )
    }

    fun isBatteryOptimizationIgnored(context: Context): Boolean {
        val pm = context.getSystemService(PowerManager::class.java) ?: return false
        return runCatching { pm.isIgnoringBatteryOptimizations(context.packageName) }
            .getOrDefault(false)
    }

    fun requestBatteryOptimizationExemption(context: Context): Boolean {
        if (isBatteryOptimizationIgnored(context)) return true
        val app = context.applicationContext
        val direct = Intent(
            Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS,
            Uri.parse("package:${app.packageName}")
        ).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)

        if (runCatching { app.startActivity(direct) }.isSuccess) return true

        val fallback = Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(fallback) }.isSuccess
    }

    fun vpnBootstrapStatus(context: Context): VpnBootstrapStatus {
        val app = context.applicationContext
        val result = DhizukuDeviceOwnerBridge.getAlwaysOnVpnPackage(app)
        val configured = result.getOrNull()
        return if (result.isSuccess) {
            VpnBootstrapStatus(
                supported = true,
                enabledForNightzuku = configured == app.packageName,
                configuredPackage = configured,
                message = when {
                    configured == app.packageName -> "Bootstrap del sistema activado para Nightzuku."
                    configured.isNullOrBlank() -> "No hay VPN always-on administrada."
                    else -> "Otra app usa VPN always-on: $configured"
                }
            )
        } else {
            VpnBootstrapStatus(
                supported = false,
                enabledForNightzuku = false,
                configuredPackage = null,
                message = result.exceptionOrNull()?.message ?: "No disponible."
            )
        }
    }

    fun setVpnBootstrap(context: Context, enabled: Boolean): Result<VpnBootstrapStatus> {
        val app = context.applicationContext
        if (enabled) {
            val current = vpnBootstrapStatus(app)
            if (current.configuredPackage != null &&
                current.configuredPackage != app.packageName) {
                return Result.failure(
                    IllegalStateException(
                        "Ya existe una VPN always-on administrada: ${current.configuredPackage}"
                    )
                )
            }
        }

        return DhizukuDeviceOwnerBridge
            .setNightzukuAlwaysOnVpn(app, enabled)
            .map { vpnBootstrapStatus(app) }
    }

    fun isHonorMagicOsDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.orEmpty().lowercase()
        val brand = Build.BRAND.orEmpty().lowercase()
        return manufacturer.contains("honor") || brand.contains("honor")
    }

    /**
     * HONOR/MagicOS has an OEM App launch policy in addition to Android's
     * battery-optimization whitelist. It cannot be granted by a normal app,
     * so Nightzuku opens the vendor page and lets the user enable:
     * Auto-launch, Secondary launch and Run in background.
     */
    fun openHonorAppLaunchSettings(context: Context): Boolean {
        val app = context.applicationContext
        val candidates = listOf(
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.hihonor.systemmanager",
                "com.hihonor.systemmanager.optimize.process.ProtectActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.startupmgr.ui.StartupNormalAppListActivity"
            ),
            ComponentName(
                "com.huawei.systemmanager",
                "com.huawei.systemmanager.optimize.process.ProtectActivity"
            )
        )

        for (component in candidates) {
            val intent = Intent()
                .setComponent(component)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            if (runCatching { app.startActivity(intent) }.isSuccess) return true
        }

        val fallback = Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
            .setData(Uri.parse("package:${app.packageName}"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        return runCatching { app.startActivity(fallback) }.isSuccess
    }

    fun openDeveloperOptions(context: Context): Boolean {
        return AndroidDebugSettingsIntents.openDeveloperOptions(context)
    }

    fun openWirelessDebugging(context: Context): Boolean {
        return AndroidDebugSettingsIntents.openWirelessDebugging(context)
    }

    fun developerOptionsSnapshot(context: Context): DeveloperOptionsController.Snapshot {
        return DeveloperOptionsController.snapshot(context)
    }

    suspend fun prepareDeveloperOptionsControl(context: Context): DeveloperOptionsController.Result {
        return DeveloperOptionsController.prepare(context)
    }

    suspend fun disableDeveloperOptionsTemporarily(context: Context): DeveloperOptionsController.Result {
        return DeveloperOptionsController.disableTemporarily(context)
    }

    suspend fun restoreDeveloperOptions(context: Context): DeveloperOptionsController.Result {
        val result = DeveloperOptionsController.restore(context)
        if (result.success) {
            // adbd may need a short settle after the global settings are restored.
            delay(1_000L)
            NightDogRecovery.requestImmediateRecovery(context)
        }
        return result
    }

    fun retryRemainingMs(snapshot: NightDogRecovery.Snapshot, nowElapsedRealtime: Long = SystemClock.elapsedRealtime()): Long {
        return NightDogBackoff.remainingRetryMs(
            nowElapsedRealtime,
            snapshot.lastAttemptElapsedRealtime,
            snapshot.failedAttempts
        )
    }

    private const val DEFAULT_HOST = "127.0.0.1"
    private const val DEFAULT_PORT = 5555
}
