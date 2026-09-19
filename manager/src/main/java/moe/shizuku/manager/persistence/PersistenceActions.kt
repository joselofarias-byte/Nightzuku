package moe.shizuku.manager.persistence

import android.content.Context
import android.os.SystemClock
import kotlinx.coroutines.delay
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbRecoveryTestController
import moe.shizuku.manager.adb.AdbTcpController
import moe.shizuku.manager.shizuku.NightDogRecovery
import rikka.shizuku.Shizuku

data class RecoveryTestReport(
    val success: Boolean,
    val killedPid: Int?,
    val recoveredPid: Int?,
    val elapsedSeconds: Int?,
    val lastStage: String
)

object PersistenceActions {

    fun recoverNow(context: Context) {
        NightDogRecovery.requestImmediateRecovery(context)
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

    fun openDeveloperOptions(context: Context): Boolean {
        return AndroidDebugSettingsIntents.openDeveloperOptions(context)
    }

    fun openWirelessDebugging(context: Context): Boolean {
        return AndroidDebugSettingsIntents.openWirelessDebugging(context)
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
