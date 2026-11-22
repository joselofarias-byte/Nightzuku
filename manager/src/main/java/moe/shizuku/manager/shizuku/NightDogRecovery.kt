package moe.shizuku.manager.shizuku

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.database.ContentObserver
import android.net.ConnectivityManager
import android.net.LinkProperties
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.os.SystemClock
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.EOFException
import java.net.ConnectException
import java.net.NoRouteToHostException
import java.net.SocketException
import java.net.SocketTimeoutException
import java.net.UnknownHostException
import javax.net.ssl.SSLException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbAuthorizationException
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbDiscoveryResult
import moe.shizuku.manager.adb.AdbEndpoint
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbOfflineException
import moe.shizuku.manager.adb.AdbShellUnavailableException
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.adb.WirelessDebuggingRestoreResult
import moe.shizuku.manager.starter.Starter
import rikka.shizuku.Shizuku

internal interface RecoveryAdbSession : Closeable {
    fun connect()
    fun verifyShell(): Boolean
    fun startServer()
    fun queryServerPid(): Int?
}

internal object AdbFailureClassifier {
    private const val MAX_CAUSE_DEPTH = 8

    fun classify(error: Throwable): NightDogRecovery.FailureCategory {
        val chain = causeChain(error)
        for (item in chain) {
            when (item) {
                is AdbAuthorizationException,
                is AdbKeyException,
                is SSLException -> return NightDogRecovery.FailureCategory.AUTH_FAILED
                is AdbShellUnavailableException -> return NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE
                is AdbOfflineException -> return NightDogRecovery.FailureCategory.OFFLINE
            }
        }
        for (item in chain) {
            when (item) {
                is ConnectException,
                is SocketTimeoutException,
                is NoRouteToHostException,
                is UnknownHostException,
                is SocketException,
                is EOFException -> return NightDogRecovery.FailureCategory.OFFLINE
            }
        }
        return NightDogRecovery.FailureCategory.UNKNOWN
    }

    private fun causeChain(error: Throwable): List<Throwable> {
        val result = ArrayList<Throwable>(4)
        var current: Throwable? = error
        var depth = 0
        while (current != null && depth < MAX_CAUSE_DEPTH) {
            result.add(current)
            val next = current.cause
            if (next === current) break
            current = next
            depth++
        }
        return result
    }
}

private object DefaultUnitTestSystemEnvironment : NightDogSystemEnvironment {
    override fun isWifiConnected(): Boolean = true
    override fun hasLocalPersistentTcpEndpoint(): Boolean = false
    override fun isAutoRestoreWirelessEnabled(): Boolean = false
    override fun tryEnableWirelessDebugging(): WirelessDebuggingRestoreResult =
        WirelessDebuggingRestoreResult.NotPermitted
    override fun isIgnoringBatteryOptimizations(): Boolean? = null
}

internal class NightDogRecoveryEngine(
    private val scope: CoroutineScope,
    private val clockElapsedMs: () -> Long,
    private val delayMs: suspend (Long) -> Unit,
    private val isDesiredRunning: () -> Boolean,
    private val setDesiredRunning: (Boolean) -> Unit,
    private val pingBinder: () -> Boolean,
    private val discoverEndpoint: suspend () -> AdbDiscoveryResult,
    private val openSession: (AdbEndpoint) -> RecoveryAdbSession,
    private val systemEnv: NightDogSystemEnvironment = DefaultUnitTestSystemEnvironment,
    private val scheduler: NightDogSchedulerPort = NoOpNightDogSchedulerPort,
    private val logWarning: (String) -> Unit = { Log.w(TAG, it) },
    private val logDebug: (String) -> Unit = { Log.d(TAG, it) },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val healthyPollIntervalMs: Long = HEALTHY_POLL_INTERVAL_MS,
    private val recoverySettleMs: Long = RECOVERY_SETTLE_MS,
    private val wirelessEnableSettleMs: Long = WIRELESS_ENABLE_SETTLE_MS,
    private val binderConfirmTimeoutMs: Long = BINDER_CONFIRM_TIMEOUT_MS,
    private val binderConfirmPollMs: Long = BINDER_CONFIRM_POLL_MS,
    private val minRetryMs: Long = MIN_RETRY_MS,
    private val maxRetryMs: Long = MAX_RETRY_MS,
    private val discoverAlternateEndpoint: suspend (AdbEndpoint) -> AdbEndpoint? = { null },
    private val isWirelessDebuggingDisabled: () -> Boolean = { false }
) {

    private val lock = Any()

    private var started = false
    private var pollingJob: Job? = null
    private var recoveryJob: Job? = null
    private var pidJob: Job? = null
    private var recoverySession: RecoveryAdbSession? = null
    private var pidSession: RecoveryAdbSession? = null
    private var attemptSequence = 0L
    private var activeAttemptId = 0L
    private var pidSequence = 0L
    private var activePidId = 0L

    private var failedAttempts = 0
    private var lastAttemptAt = 0L
    private var runningSinceAt = 0L
    private var currentServerPid: Int? = null
    private var recoveryCount = 0
    private var lastBinderLostAt = 0L
    private var lastRecoveryAt = 0L

    private var lastWifiConnected: Boolean? = null
    private var lastNetworkFingerprint: String? = null
    private var lastNetworkTransitionAt = 0L
    private var lastResumeTriggerAt = 0L
    private var wirelessDisabledByUser = false
    private var autoRestoreRejectedInEpoch = false
    private var lastAutoRestoreAt = 0L
    private var wirelessAutoRestoreAttempted = false

    private val recoveryTimeline = java.util.ArrayDeque<String>()
    private val _snapshot = MutableStateFlow(NightDogRecovery.Snapshot())
    val snapshot: StateFlow<NightDogRecovery.Snapshot> = _snapshot.asStateFlow()

    fun retryDelayMs(attempts: Int = failedAttempts): Long {
        if (attempts <= 0) return 0L
        val shift = (attempts - 1).coerceIn(0, 3)
        return (minRetryMs shl shift).coerceAtMost(maxRetryMs)
    }

    fun isWirelessDisabledByUserForTest(): Boolean = synchronized(lock) { wirelessDisabledByUser }

    fun start(startPollingLoop: Boolean = true) {
        var shouldSchedulePeriodic = false
        var shouldRefreshPid = false
        synchronized(lock) {
            if (started) return
            started = true
            lastWifiConnected = systemEnv.isWifiConnected()
            if (startPollingLoop) {
                pollingJob = scope.launch {
                    while (isActive) {
                        pollOnce()
                        delayMs(nextPollIntervalMs())
                    }
                }
            }
            if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada",
                    binderAlive = pingBinder(),
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
            } else if (pingBinder()) {
                shouldRefreshPid = markRunningLocked("Inicio de la app: Binder activo")
                shouldSchedulePeriodic = true
            } else {
                shouldSchedulePeriodic = true
                publishLocked(
                    stage = NightDogRecovery.Stage.CHECKING_BINDER,
                    result = "Inicio de la app: servicio deseado activo; comprobando ADB",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
                requestRecoveryLocked()
            }
        }
        if (shouldSchedulePeriodic) {
            scheduler.schedulePeriodicHealthCheck()
        }
        if (shouldRefreshPid) {
            refreshServerPid()
        }
    }

    fun stop() {
        val recToClose: RecoveryAdbSession?
        val pidToClose: RecoveryAdbSession?
        synchronized(lock) {
            started = false
            activeAttemptId = ++attemptSequence
            activePidId = ++pidSequence
            pollingJob?.cancel()
            pollingJob = null
            recoveryJob?.cancel()
            recoveryJob = null
            recToClose = recoverySession
            recoverySession = null
            pidJob?.cancel()
            pidJob = null
            pidToClose = pidSession
            pidSession = null
            failedAttempts = 0
            lastAttemptAt = 0L
            runningSinceAt = 0L
            currentServerPid = null
            recoveryCount = 0
            lastBinderLostAt = 0L
            lastRecoveryAt = 0L
            wirelessDisabledByUser = false
            autoRestoreRejectedInEpoch = false
            lastAutoRestoreAt = 0L
            wirelessAutoRestoreAttempted = false
            _snapshot.value = NightDogRecovery.Snapshot(
                stage = NightDogRecovery.Stage.IDLE,
                desiredRunning = false,
                lastResult = "Watchdog detenido"
            )
        }
        runCatching { recToClose?.close() }
        runCatching { pidToClose?.close() }
    }

    fun onBinderReceived() {
        val recToClose: RecoveryAdbSession?
        var shouldRefreshPid = false
        var shouldClearRecoveryState = false
        synchronized(lock) {
            if (!started) return
            if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada",
                    binderAlive = pingBinder(),
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
                return
            }
            // No cancelar un intento de recuperación activo por un callback tardío/obsoleto
            // si el Binder realmente no responde al ping.
            if (!pingBinder()) {
                return
            }
            activeAttemptId = ++attemptSequence
            recoveryJob?.cancel()
            recoveryJob = null
            recToClose = recoverySession
            recoverySession = null
            if (markRunningLocked("Binder recibido; servicio activo")) {
                shouldRefreshPid = true
                shouldClearRecoveryState = true
            }
        }
        runCatching { recToClose?.close() }
        if (shouldClearRecoveryState) {
            scheduler.cancelRecoveryRetries()
            scheduler.clearActionNotification()
            scheduler.schedulePeriodicHealthCheck()
        }
        if (shouldRefreshPid) refreshServerPid()
    }

    fun onBinderDead() {
        val pidToClose: RecoveryAdbSession?
        synchronized(lock) {
            if (!started) return
            activePidId = ++pidSequence
            pidJob?.cancel()
            pidJob = null
            pidToClose = pidSession
            pidSession = null
            lastBinderLostAt = clockElapsedMs()
            runningSinceAt = 0L
            currentServerPid = null
            if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
            } else {
                if (recoveryJob?.isActive != true) {
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Binder perdido; iniciando recuperación",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.NONE,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                }
                requestRecoveryLocked()
            }
        }
        runCatching { pidToClose?.close() }
    }

    fun onBootUnlocked() {
        var shouldScheduleNetwork = false
        var shouldSchedulePeriodic = false
        synchronized(lock) {
            if (!started) return
            if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada tras reinicio",
                    binderAlive = pingBinder(),
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
                return
            }
            // Tras un reinicio del sistema, Android/MagicOS suele desactivar adb_wifi_enabled:
            // esto no corresponde a una desactivación explícita del usuario.
            wirelessDisabledByUser = false
            autoRestoreRejectedInEpoch = false
            lastNetworkTransitionAt = clockElapsedMs()
            failedAttempts = 0
            lastAttemptAt = 0L
            shouldSchedulePeriodic = true
            if (pingBinder()) {
                markRunningLocked("Binder activo tras el arranque")
            } else {
                publishLocked(
                    stage = NightDogRecovery.Stage.CHECKING_BINDER,
                    result = "Arranque desbloqueado; iniciando recuperación ADB",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
                requestRecoveryLocked()
                shouldScheduleNetwork = true
            }
        }
        if (shouldSchedulePeriodic) scheduler.schedulePeriodicHealthCheck()
        if (shouldScheduleNetwork) scheduleNetworkRetry(minRetryMs)
    }

    fun onNetworkChanged(wifiConnected: Boolean, networkFingerprint: String) {
        var shouldScheduleNetwork = false
        synchronized(lock) {
            if (!started) return
            val now = clockElapsedMs()
            val prevWifi = lastWifiConnected
            val prevFingerprint = lastNetworkFingerprint
            val changed = prevWifi != wifiConnected ||
                (prevFingerprint != null && prevFingerprint != networkFingerprint)
            lastWifiConnected = wifiConnected
            lastNetworkFingerprint = networkFingerprint

            if (!isDesiredRunning()) {
                return
            }

            if (changed) {
                lastNetworkTransitionAt = now
                autoRestoreRejectedInEpoch = false
            }

            if (pingBinder()) {
                // Binder saludable: no perturbar ni reiniciar ADB al cambiar de red.
                return
            }

            if (!wifiConnected && !systemEnv.hasLocalPersistentTcpEndpoint()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                    result = "Wi-Fi desconectado; se conserva la recuperación pendiente hasta reconectar",
                    binderAlive = false,
                    transport = "sin Wi-Fi",
                    endpoint = null,
                    failureCategory = NightDogRecovery.FailureCategory.WIFI_DISCONNECTED,
                    actionHint = NightDogRecovery.ActionHint.CONNECT_WIFI
                )
                shouldScheduleNetwork = true
            } else if (changed && wifiConnected) {
                // Reconexión de Wi-Fi, cambio de AP o cambio de IP: redescubrir inmediatamente
                // un endpoint fresco sin esperar el backoff anterior.
                failedAttempts = 0
                lastAttemptAt = 0L
                if (recoveryJob?.isActive != true) {
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Red Wi-Fi disponible/actualizada; redescubriendo endpoint ADB",
                        binderAlive = false,
                        endpoint = null,
                        failureCategory = NightDogRecovery.FailureCategory.NONE,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                    requestRecoveryLocked()
                }
            }
        }
        if (shouldScheduleNetwork) {
            scheduleNetworkRetry(minRetryMs)
        }
    }

    fun onWirelessDebuggingSettingChanged(enabled: Boolean) {
        var shouldArmTrigger = false
        synchronized(lock) {
            if (!started) return
            if (!isDesiredRunning()) return

            val now = clockElapsedMs()
            if (enabled) {
                wirelessDisabledByUser = false
                autoRestoreRejectedInEpoch = false
                if (!pingBinder()) {
                    failedAttempts = 0
                    lastAttemptAt = 0L
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Depuración inalámbrica activada; redescubriendo endpoint ADB",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.NONE,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                    requestRecoveryLocked()
                }
            } else {
                val wifiNow = systemEnv.isWifiConnected()
                val recentNetworkChange = lastNetworkTransitionAt > 0L &&
                    (now - lastNetworkTransitionAt) <= NETWORK_TRANSITION_GRACE_MS
                if (wifiNow && !recentNetworkChange) {
                    // El ajuste cambió 1 -> 0 con Wi-Fi estable: respetar decisión explícita del usuario.
                    wirelessDisabledByUser = true
                }
                if (!pingBinder()) {
                    val message = if (wirelessDisabledByUser) {
                        "Depuración inalámbrica desactivada manualmente. Actívala en Opciones de desarrollador para reanudar"
                    } else {
                        "Depuración inalámbrica desactivada por Android/MagicOS tras cambio de red"
                    }
                    publishLocked(
                        stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                        result = message,
                        binderAlive = false,
                        transport = "desactivado",
                        endpoint = null,
                        failureCategory = NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                        actionHint = NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING
                    )
                    shouldArmTrigger = true
                }
            }
        }
        if (shouldArmTrigger) {
            scheduler.scheduleWirelessSettingTrigger()
        }
    }

    fun onDeviceResume() {
        synchronized(lock) {
            if (!started || !isDesiredRunning() || pingBinder()) return
            val now = clockElapsedMs()
            if (lastResumeTriggerAt != 0L && now - lastResumeTriggerAt < EVENT_DEBOUNCE_MS) {
                return
            }
            lastResumeTriggerAt = now
            if (wirelessDisabledByUser ||
                _snapshot.value.failureCategory == NightDogRecovery.FailureCategory.AUTH_FAILED
            ) {
                return
            }
            if (recoveryJob?.isActive != true) {
                // Reevaluar tras salir de suspensión/Doze sin esperar un backoff largo antiguo.
                lastAttemptAt = 0L
                requestRecoveryLocked()
            }
        }
    }

    fun onScheduledWakeup(reason: String) {
        synchronized(lock) {
            if (!started || !isDesiredRunning()) return
            recordRetryEventLocked("Aviso programado recibido · $reason")
            if (pingBinder()) {
                markRunningLocked("Binder verificado en comprobación programada ($reason)")
                return
            }
            if (recoveryJob?.isActive == true) return
            if (_snapshot.value.failureCategory == NightDogRecovery.FailureCategory.AUTH_FAILED ||
                wirelessDisabledByUser
            ) {
                return
            }
            lastAttemptAt = 0L
            requestRecoveryLocked()
        }
    }

    fun requestManualStart(recoverImmediately: Boolean = true) {
        var shouldClearNotification = false
        var shouldSchedulePeriodic = false
        synchronized(lock) {
            setDesiredRunning(true)
            wirelessDisabledByUser = false
            autoRestoreRejectedInEpoch = false
            lastAutoRestoreAt = 0L
            failedAttempts = 0
            lastAttemptAt = 0L
            shouldClearNotification = true
            shouldSchedulePeriodic = started
            if (pingBinder()) {
                markRunningLocked("Binder ya está activo")
            } else {
                publishLocked(
                    stage = NightDogRecovery.Stage.CHECKING_BINDER,
                    result = "Inicio solicitado; comprobando Binder y ADB",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
                if (recoverImmediately && started) {
                    requestRecoveryLocked()
                }
            }
        }
        if (shouldClearNotification) scheduler.clearActionNotification()
        if (shouldSchedulePeriodic) scheduler.schedulePeriodicHealthCheck()
    }

    fun prepareForManualStop() {
        val recToClose: RecoveryAdbSession?
        val pidToClose: RecoveryAdbSession?
        synchronized(lock) {
            setDesiredRunning(false)
            activeAttemptId = ++attemptSequence
            activePidId = ++pidSequence
            recoveryJob?.cancel()
            recoveryJob = null
            recToClose = recoverySession
            recoverySession = null
            pidJob?.cancel()
            pidJob = null
            pidToClose = pidSession
            pidSession = null
            failedAttempts = 0
            lastAttemptAt = 0L
            runningSinceAt = 0L
            currentServerPid = null
            wirelessDisabledByUser = false
            autoRestoreRejectedInEpoch = false
            publishLocked(
                stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                result = "Detenido manualmente; recuperación deshabilitada",
                binderAlive = pingBinder(),
                transport = null,
                endpoint = null,
                failureCategory = NightDogRecovery.FailureCategory.NONE,
                actionHint = NightDogRecovery.ActionHint.NONE
            )
        }
        runCatching { recToClose?.close() }
        runCatching { pidToClose?.close() }
        scheduler.cancelAllJobs()
        scheduler.clearActionNotification()
    }

    fun pollOnce() {
        var clearedRetries = false
        synchronized(lock) {
            if (!started) return
            val alive = pingBinder()
            if (!isDesiredRunning()) {
                runningSinceAt = 0L
                currentServerPid = null
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Servicio detenido manualmente",
                    binderAlive = alive,
                    failureCategory = NightDogRecovery.FailureCategory.NONE,
                    actionHint = NightDogRecovery.ActionHint.NONE
                )
            } else if (alive) {
                clearedRetries = markRunningLocked("Binder responde correctamente")
            } else {
                if (_snapshot.value.binderAlive) {
                    lastBinderLostAt = clockElapsedMs()
                    runningSinceAt = 0L
                    currentServerPid = null
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Binder no responde; buscando transporte ADB",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.NONE,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                }
                if (recoveryJob?.isActive != true) {
                    requestRecoveryLocked()
                }
            }
        }
        if (clearedRetries) {
            scheduler.cancelRecoveryRetries()
            scheduler.clearActionNotification()
        }
    }

    internal fun activeRecoveryJobForTest(): Job? = synchronized(lock) { recoveryJob }

    suspend fun awaitActiveRecovery(timeoutMs: Long) {
        val job = synchronized(lock) { recoveryJob } ?: return
        withTimeoutOrNull(timeoutMs) {
            job.join()
        }
    }

    private fun nextPollIntervalMs(): Long = synchronized(lock) {
        if (!isDesiredRunning()) return@synchronized healthyPollIntervalMs
        if (_snapshot.value.stage == NightDogRecovery.Stage.RUNNING && _snapshot.value.binderAlive) {
            return@synchronized healthyPollIntervalMs
        }
        val backoff = retryDelayMs()
        if (backoff > pollIntervalMs && lastAttemptAt != 0L) {
            val remaining = (lastAttemptAt + backoff - clockElapsedMs()).coerceAtLeast(pollIntervalMs)
            return@synchronized remaining.coerceAtMost(maxRetryMs)
        }
        pollIntervalMs
    }

    private fun isCurrentAttemptLocked(attemptId: Long): Boolean =
        started && attemptId == activeAttemptId && isDesiredRunning()

    private fun markRunningLocked(result: String): Boolean {
        if (!pingBinder()) return false
        val now = clockElapsedMs()
        val newlyRunning = runningSinceAt == 0L
        if (newlyRunning) runningSinceAt = now
        if (newlyRunning && lastBinderLostAt > 0L) {
            recoveryCount++
            lastRecoveryAt = now
        }
        failedAttempts = 0
        lastAttemptAt = 0L
        wirelessDisabledByUser = false
        autoRestoreRejectedInEpoch = false
        systemEnv.onServiceConfirmedRunning()
        publishLocked(
            stage = NightDogRecovery.Stage.RUNNING,
            result = result,
            binderAlive = true,
            transport = "Binder activo",
            endpoint = "no requerido",
            failureCategory = NightDogRecovery.FailureCategory.NONE,
            actionHint = NightDogRecovery.ActionHint.NONE
        )
        return true
    }

    private fun requestRecoveryLocked() {
        if (!started) return
        if (pingBinder()) {
            markRunningLocked("Binder ya está activo")
            return
        }
        if (!isDesiredRunning()) {
            publishLocked(
                stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                result = "Recuperación omitida: detención manual",
                binderAlive = false,
                failureCategory = NightDogRecovery.FailureCategory.NONE,
                actionHint = NightDogRecovery.ActionHint.NONE
            )
            return
        }
        if (recoveryJob?.isActive == true) return

        val now = clockElapsedMs()
        val retryDelay = retryDelayMs()
        if (lastAttemptAt != 0L && now - lastAttemptAt < retryDelay) {
            return
        }

        val attemptId = ++attemptSequence
        activeAttemptId = attemptId
        recordRetryEventLocked("Intento $attemptId aceptado")

        recoveryJob = scope.launch {
            runRecoveryAttempt(attemptId)
        }
    }

    private suspend fun runRecoveryAttempt(attemptId: Long) {
        var currentEndpoint: AdbEndpoint? = null
        var attemptPhase = "descubrimiento ADB"
        try {
            if (recoverySettleMs > 0L) {
                delayMs(recoverySettleMs)
            }
            val skipForNoWifi = synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder ya está activo")
                    return
                }
                val wifiNow = systemEnv.isWifiConnected()
                lastWifiConnected = wifiNow
                if (!wifiNow && !systemEnv.hasLocalPersistentTcpEndpoint()) {
                    failedAttempts++
                    lastAttemptAt = clockElapsedMs()
                    publishLocked(
                        stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                        result = "Wi-Fi desconectado; esperando reconexión para redescubrir ADB inalámbrico",
                        binderAlive = false,
                        transport = "sin Wi-Fi",
                        endpoint = null,
                        failureCategory = NightDogRecovery.FailureCategory.WIFI_DISCONNECTED,
                        actionHint = NightDogRecovery.ActionHint.CONNECT_WIFI
                    )
                    true
                } else {
                    failedAttempts++
                    lastAttemptAt = clockElapsedMs()
                    publishLocked(
                        stage = NightDogRecovery.Stage.DISCOVERING_ADB,
                        result = "Buscando endpoint ADB local actual; aún no autenticado",
                        binderAlive = false,
                        transport = null,
                        endpoint = null,
                        failureCategory = NightDogRecovery.FailureCategory.NONE,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                    false
                }
            }
            if (skipForNoWifi) {
                scheduleNetworkRetry(retryDelayMs())
                return
            }

            var discovery = discoverEndpoint()

            if (discovery is AdbDiscoveryResult.WirelessDebuggingDisabled) {
                val shouldTryAutoRestore = synchronized(lock) {
                    if (!isCurrentAttemptLocked(attemptId)) return
                    if (pingBinder()) {
                        markRunningLocked("Binder ya está activo")
                        return
                    }
                    val now = clockElapsedMs()
                    val cooldownElapsed = lastAutoRestoreAt == 0L ||
                        (now - lastAutoRestoreAt) >= AUTO_ENABLE_COOLDOWN_MS
                    val canAttempt = systemEnv.isAutoRestoreWirelessEnabled() &&
                        !wirelessDisabledByUser &&
                        !autoRestoreRejectedInEpoch &&
                        systemEnv.isWifiConnected() &&
                        cooldownElapsed
                    if (canAttempt) {
                        lastAutoRestoreAt = now
                        wirelessAutoRestoreAttempted = true
                    }
                    canAttempt
                }

                if (shouldTryAutoRestore) {
                    when (val restoreResult = systemEnv.tryEnableWirelessDebugging()) {
                        is WirelessDebuggingRestoreResult.Enabled,
                        is WirelessDebuggingRestoreResult.AlreadyEnabled -> {
                            if (wirelessEnableSettleMs > 0L) {
                                delayMs(wirelessEnableSettleMs)
                            }
                            synchronized(lock) {
                                if (!isCurrentAttemptLocked(attemptId)) return
                                if (pingBinder()) {
                                    markRunningLocked("Binder ya está activo")
                                    return
                                }
                            }
                            discovery = discoverEndpoint()
                        }
                        is WirelessDebuggingRestoreResult.SystemRejected -> {
                            val msg = "Android/MagicOS impidió reactivar la depuración inalámbrica (${restoreResult.reason}). Actívala en Opciones de desarrollador"
                            synchronized(lock) {
                                if (!isCurrentAttemptLocked(attemptId)) return
                                autoRestoreRejectedInEpoch = true
                                lastAttemptAt = clockElapsedMs()
                                publishLocked(
                                    stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                                    result = msg,
                                    binderAlive = false,
                                    transport = "desactivado",
                                    endpoint = null,
                                    failureCategory = NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                                    actionHint = NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING
                                )
                            }
                            scheduler.scheduleWirelessSettingTrigger()
                            scheduler.notifyActionRequired(
                                NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                                NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING,
                                msg
                            )
                            return
                        }
                        is WirelessDebuggingRestoreResult.DeveloperOptionsDisabled -> {
                            val msg = "Opciones de desarrollador desactivadas. Actívalas junto con Depuración inalámbrica"
                            synchronized(lock) {
                                if (!isCurrentAttemptLocked(attemptId)) return
                                lastAttemptAt = clockElapsedMs()
                                publishLocked(
                                    stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                                    result = msg,
                                    binderAlive = false,
                                    transport = "desactivado",
                                    endpoint = null,
                                    failureCategory = NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                                    actionHint = NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING
                                )
                            }
                            scheduler.scheduleWirelessSettingTrigger()
                            scheduler.notifyActionRequired(
                                NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                                NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING,
                                msg
                            )
                            return
                        }
                        is WirelessDebuggingRestoreResult.WifiRequired -> {
                            val msg = "Wi-Fi desconectado; se requiere Wi-Fi para activar depuración inalámbrica"
                            synchronized(lock) {
                                if (!isCurrentAttemptLocked(attemptId)) return
                                lastAttemptAt = clockElapsedMs()
                                publishLocked(
                                    stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                                    result = msg,
                                    binderAlive = false,
                                    transport = "sin Wi-Fi",
                                    endpoint = null,
                                    failureCategory = NightDogRecovery.FailureCategory.WIFI_DISCONNECTED,
                                    actionHint = NightDogRecovery.ActionHint.CONNECT_WIFI
                                )
                            }
                            scheduleNetworkRetry(retryDelayMs())
                            return
                        }
                        is WirelessDebuggingRestoreResult.NotPermitted -> {
                            // Continúa hacia el diagnóstico estándar sin afirmar capacidad de escritura protegida.
                        }
                    }
                }
            }

            var postDiscoverySchedule: (() -> Unit)? = null
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder ya está activo")
                    return
                }
                when (discovery) {
                    is AdbDiscoveryResult.WirelessDebuggingDisabled -> {
                        lastAttemptAt = clockElapsedMs()
                        val userDisabled = wirelessDisabledByUser
                        val msg = if (userDisabled) {
                            "Depuración inalámbrica desactivada manualmente. Actívala en Opciones de desarrollador para reanudar"
                        } else {
                            "Depuración inalámbrica desactivada por Android/MagicOS o sin permiso WRITE_SECURE_SETTINGS. Actívala en Opciones de desarrollador"
                        }
                        publishLocked(
                            stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                            result = msg,
                            binderAlive = false,
                            transport = "desactivado",
                            endpoint = null,
                            failureCategory = NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                            actionHint = NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING
                        )
                        postDiscoverySchedule = {
                            scheduler.scheduleWirelessSettingTrigger()
                            if (!userDisabled) {
                                scheduler.notifyActionRequired(
                                    NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED,
                                    NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING,
                                    msg
                                )
                            }
                        }
                    }
                    is AdbDiscoveryResult.Absent -> {
                        lastAttemptAt = clockElapsedMs()
                        val delayForJob = retryDelayMs()
                        publishLocked(
                            stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                            result = "Endpoint ADB local ausente. Comprueba Wi-Fi, depuración inalámbrica y emparejamiento",
                            binderAlive = false,
                            transport = "ninguno",
                            endpoint = null,
                            failureCategory = NightDogRecovery.FailureCategory.ENDPOINT_ABSENT,
                            actionHint = NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING
                        )
                        postDiscoverySchedule = {
                            scheduleNetworkRetry(delayForJob)
                            scheduler.scheduleWirelessSettingTrigger()
                        }
                    }
                    is AdbDiscoveryResult.Found -> {
                        currentEndpoint = discovery.endpoint
                    }
                }
            }
            postDiscoverySchedule?.let {
                it.invoke()
                return
            }

            var usedAlternate = false
            while (true) {
                var launchSubmitted = false
                try {
                    val endpoint = currentEndpoint ?: return
                    val session = openSession(endpoint)
                    val registered = synchronized(lock) {
                        if (!isCurrentAttemptLocked(attemptId)) {
                            false
                        } else if (pingBinder()) {
                            markRunningLocked("Binder ya está activo")
                            false
                        } else {
                            recoverySession = session
                            true
                        }
                    }
                    if (!registered) {
                        runCatching { session.close() }
                        return
                    }

                    try {
                        attemptPhase = "conexión y autenticación ADB"
                        session.connect()
                        synchronized(lock) {
                            if (!isCurrentAttemptLocked(attemptId)) return
                            if (pingBinder()) {
                                markRunningLocked("Binder ya está activo")
                                return
                            }
                        }
                        attemptPhase = "verificación de shell"
                        if (!session.verifyShell()) {
                            throw AdbShellUnavailableException("El transporte ADB no permitió ejecutar un comando shell")
                        }
                        synchronized(lock) {
                            if (!isCurrentAttemptLocked(attemptId)) return
                            if (pingBinder()) {
                                markRunningLocked("Binder ya está activo")
                                return
                            }
                            val transportLabel = if (endpoint.requireTls) {
                                "ADB TLS verificado"
                            } else {
                                "ADB TCP local verificado"
                            }
                            publishLocked(
                                stage = NightDogRecovery.Stage.STARTING_SERVICE,
                                result = "ADB autenticado y shell verificado; iniciando servicio",
                                binderAlive = false,
                                transport = transportLabel,
                                endpoint = "${endpoint.host}:${endpoint.port}",
                                failureCategory = NightDogRecovery.FailureCategory.NONE,
                                actionHint = NightDogRecovery.ActionHint.NONE
                            )
                        }
                        attemptPhase = "inicio del servidor"
                        launchSubmitted = true
                    session.startServer()
                    } finally {
                        synchronized(lock) {
                            if (recoverySession === session) recoverySession = null
                        }
                        runCatching { session.close() }
                    }
                    break
                } catch (error: CancellationException) {
                    throw error
                } catch (error: Exception) {
                    val previous = currentEndpoint ?: throw error
                    if (usedAlternate || !AdbTransportResolver.canTryAlternate(error, launchSubmitted)) {
                        throw error
                    }
                    if (!previous.requireTls) {
                        val restored = synchronized(lock) {
                            if (!isCurrentAttemptLocked(attemptId)) return
                            if (pingBinder()) {
                                markRunningLocked("Binder ya está activo")
                                return
                            }

                            val now = clockElapsedMs()
                            val cooldownElapsed = lastAutoRestoreAt == 0L ||
                                now - lastAutoRestoreAt >= AUTO_ENABLE_COOLDOWN_MS
                            if (!isWirelessDebuggingDisabled() ||
                                !systemEnv.isAutoRestoreWirelessEnabled() ||
                                wirelessDisabledByUser || autoRestoreRejectedInEpoch ||
                                !systemEnv.isWifiConnected() || !cooldownElapsed
                            ) {
                                false
                            } else {
                                lastAutoRestoreAt = now
                                wirelessAutoRestoreAttempted = true
                                val result = systemEnv.tryEnableWirelessDebugging()
                                if (result is WirelessDebuggingRestoreResult.SystemRejected) {
                                    autoRestoreRejectedInEpoch = true
                                }
                                recordRetryEventLocked("TCP local falló · restauración de ADB inalámbrico: $result")
                                result is WirelessDebuggingRestoreResult.Enabled ||
                                    result is WirelessDebuggingRestoreResult.AlreadyEnabled
                            }
                        }
                        if (restored && wirelessEnableSettleMs > 0L) delayMs(wirelessEnableSettleMs)
                    }
                    val alternate = discoverAlternateEndpoint(previous) ?: throw error
                    synchronized(lock) {
                        if (!isCurrentAttemptLocked(attemptId)) return
                        recordRetryEventLocked(
                            "Intento $attemptId · ${previous.host}:${previous.port} → ${alternate.host}:${alternate.port}"
                        )
                    }
                    currentEndpoint = alternate
                    usedAlternate = true
                }
            }

            attemptPhase = "confirmación de Binder"
            val confirmed = awaitBinderConfirmation(attemptId)
            var shouldRefreshPid = false
            var retryAfterNoBinderDelay: Long? = null
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (confirmed && pingBinder()) {
                    shouldRefreshPid = markRunningLocked("Binder verificado tras la recuperación")
                } else {
                    lastAttemptAt = clockElapsedMs()
                    retryAfterNoBinderDelay = retryDelayMs()
                    publishLocked(
                        stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                        result = "Inicio enviado por ADB, pero Binder aún no responde; se reintentará",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.BINDER_NOT_CONFIRMED,
                        actionHint = NightDogRecovery.ActionHint.NONE
                    )
                }
            }
            if (shouldRefreshPid) {
                scheduler.cancelRecoveryRetries()
                scheduler.clearActionNotification()
                scheduler.schedulePeriodicHealthCheck()
                refreshServerPid()
            } else if (retryAfterNoBinderDelay != null) {
                scheduleNetworkRetry(retryAfterNoBinderDelay!!)
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            var postErrorAction: (() -> Unit)? = null
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder responde correctamente")
                    return
                }
                lastAttemptAt = clockElapsedMs()
                val category = AdbFailureClassifier.classify(error)
                val endpointText = currentEndpoint?.let { "${it.host}:${it.port}" }
                val nextDelay = retryDelayMs()
                recordRecoveryEventLocked(
                    "Intento $failedAttempts · $attemptPhase · $category · " +
                        "${endpointText ?: "sin endpoint"} · ${error.javaClass.simpleName}"
                )
                logWarning("Recuperación fallida ($category): ${error.javaClass.simpleName}")
                when (category) {
                    NightDogRecovery.FailureCategory.AUTH_FAILED -> {
                        val msg = "Fallo de autorización o emparejamiento ADB revocado. Vuelve a emparejar desde Nightzuku"
                        publishLocked(
                            stage = NightDogRecovery.Stage.ERROR,
                            result = msg,
                            binderAlive = false,
                            endpoint = endpointText,
                            failureCategory = category,
                            actionHint = NightDogRecovery.ActionHint.OPEN_PAIRING
                        )
                        postErrorAction = {
                            scheduler.notifyActionRequired(
                                category,
                                NightDogRecovery.ActionHint.OPEN_PAIRING,
                                msg
                            )
                        }
                    }
                    NightDogRecovery.FailureCategory.OFFLINE -> {
                        publishLocked(
                            stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                            result = if (endpointText != null) {
                                "Endpoint ADB offline o inaccesible ($endpointText). Se reintentará"
                            } else {
                                "Endpoint ADB offline o inaccesible. Se reintentará"
                            },
                            binderAlive = false,
                            endpoint = endpointText,
                            failureCategory = category,
                            actionHint = NightDogRecovery.ActionHint.NONE
                        )
                        postErrorAction = { scheduleNetworkRetry(nextDelay) }
                    }
                    NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE -> {
                        publishLocked(
                            stage = NightDogRecovery.Stage.ERROR,
                            result = "ADB conectado, pero el transporte no permitió ejecutar shell. Se reintentará",
                            binderAlive = false,
                            endpoint = endpointText,
                            failureCategory = category,
                            actionHint = NightDogRecovery.ActionHint.NONE
                        )
                        postErrorAction = { scheduleNetworkRetry(nextDelay) }
                    }
                    else -> {
                        publishLocked(
                            stage = NightDogRecovery.Stage.ERROR,
                            result = "Falló la recuperación ADB (${error.javaClass.simpleName}). Se reintentará",
                            binderAlive = false,
                            endpoint = endpointText,
                            failureCategory = category,
                            actionHint = NightDogRecovery.ActionHint.NONE
                        )
                        postErrorAction = { scheduleNetworkRetry(nextDelay) }
                    }
                }
            }
            postErrorAction?.invoke()
        } finally {
            synchronized(lock) {
                if (activeAttemptId == attemptId) {
                    recoveryJob = null
                }
            }
        }
    }

    private suspend fun awaitBinderConfirmation(attemptId: Long): Boolean {
        val deadline = clockElapsedMs() + binderConfirmTimeoutMs
        while (true) {
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return false
            }
            if (pingBinder()) return true
            val remaining = deadline - clockElapsedMs()
            if (remaining <= 0L) return false
            delayMs(minOf(binderConfirmPollMs, remaining))
        }
    }

    private fun refreshServerPid() {
        val pidId: Long
        synchronized(lock) {
            if (!started || !pingBinder() || !isDesiredRunning() || pidJob?.isActive == true) return
            pidId = ++pidSequence
            activePidId = pidId
            // PID es solo diagnóstico; se resuelve una vez por conexión de Binder.
            pidJob = scope.launch {
                runPidLookup(pidId)
            }
        }
    }

    private suspend fun runPidLookup(pidId: Long) {
        try {
            val discovery = discoverEndpoint()
            val endpoint = (discovery as? AdbDiscoveryResult.Found)?.endpoint ?: return
            synchronized(lock) {
                if (!started || pidId != activePidId || !isDesiredRunning() || !pingBinder()) return
            }
            val session = openSession(endpoint)
            val registered = synchronized(lock) {
                if (!started || pidId != activePidId || !isDesiredRunning() || !pingBinder()) {
                    false
                } else {
                    pidSession = session
                    true
                }
            }
            if (!registered) {
                runCatching { session.close() }
                return
            }
            val pid = try {
                session.connect()
                synchronized(lock) {
                    if (!started || pidId != activePidId || !isDesiredRunning() || !pingBinder()) return
                }
                session.queryServerPid()
            } finally {
                synchronized(lock) {
                    if (pidSession === session) pidSession = null
                }
                runCatching { session.close() }
            }
            synchronized(lock) {
                if (started && pidId == activePidId && pid != null && isDesiredRunning() && pingBinder()) {
                    currentServerPid = pid
                    markRunningLocked("Binder responde correctamente")
                }
            }
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            // Diagnóstico ADB nunca debe degradar un Binder activo.
            logDebug("PID no disponible: ${error.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                if (activePidId == pidId) {
                    pidJob = null
                }
            }
        }
    }

    private fun scheduleNetworkRetry(delayMs: Long) {
        synchronized(lock) {
            if (started && isDesiredRunning()) {
                recordRetryEventLocked("Solicitado reintento de red · espera mínima ${delayMs}ms")
            }
        }
        scheduler.scheduleNetworkRecovery(delayMs)
    }

    private fun recordRetryEventLocked(detail: String) {
        recordRecoveryEventLocked(detail)
        _snapshot.value = _snapshot.value.copy(recoveryTimeline = recoveryTimeline.toList())
    }

    private fun recordRecoveryEventLocked(detail: String) {
        if (recoveryTimeline.lastOrNull()?.substringAfter(' ') == detail) return
        val time = java.text.SimpleDateFormat("HH:mm:ss.SSS", java.util.Locale.ROOT)
            .format(java.util.Date())
        if (recoveryTimeline.size >= 32) recoveryTimeline.removeFirst()
        recoveryTimeline.addLast("$time $detail")
    }

    private fun publishLocked(
        stage: NightDogRecovery.Stage,
        result: String,
        binderAlive: Boolean = pingBinder(),
        transport: String? = _snapshot.value.transport,
        endpoint: String? = _snapshot.value.endpoint,
        failureCategory: NightDogRecovery.FailureCategory = _snapshot.value.failureCategory,
        actionHint: NightDogRecovery.ActionHint = _snapshot.value.actionHint
    ) {
        if (!started && stage != NightDogRecovery.Stage.MANUALLY_STOPPED) return
        val desired = isDesiredRunning()
        val previous = _snapshot.value
        val changed = stage != previous.stage || failureCategory != previous.failureCategory ||
            failedAttempts != previous.failedAttempts || endpoint != previous.endpoint
        if (desired && changed) {
            val delay = retryDelayMs()
            val retry = if (delay > 0L && failureCategory != NightDogRecovery.FailureCategory.NONE &&
                failureCategory != NightDogRecovery.FailureCategory.AUTH_FAILED
            ) " · espera mínima interna ${delay / 1000}s" else ""
            recordRecoveryEventLocked(
                "$stage · $failureCategory · ${endpoint ?: "sin endpoint"} · $result$retry"
            )
        }
        _snapshot.value = NightDogRecovery.Snapshot(
            stage = if (desired) stage else NightDogRecovery.Stage.MANUALLY_STOPPED,
            desiredRunning = desired,
            binderAlive = binderAlive,
            transport = transport,
            endpoint = endpoint,
            failedAttempts = failedAttempts,
            lastResult = if (desired) result else "Detenido manualmente; recuperación deshabilitada",
            lastAttemptElapsedRealtime = lastAttemptAt,
            runningSinceElapsedRealtime = runningSinceAt,
            serverPid = currentServerPid,
            recoveryCount = recoveryCount,
            lastBinderLostElapsedRealtime = lastBinderLostAt,
            lastRecoveryElapsedRealtime = lastRecoveryAt,
            failureCategory = if (desired) failureCategory else NightDogRecovery.FailureCategory.NONE,
            actionHint = if (desired) actionHint else NightDogRecovery.ActionHint.NONE,
            wifiConnected = lastWifiConnected,
            wirelessAutoRestoreAttempted = wirelessAutoRestoreAttempted,
            batteryOptimizationIgnored = systemEnv.isIgnoringBatteryOptimizations(),
            recoveryTimeline = recoveryTimeline.toList()
        )
    }

    companion object {
        private const val TAG = "NightDogRecovery"
        internal const val POLL_INTERVAL_MS = 4_000L
        internal const val HEALTHY_POLL_INTERVAL_MS = 15_000L
        internal const val RECOVERY_SETTLE_MS = 1_500L
        internal const val WIRELESS_ENABLE_SETTLE_MS = 1_500L
        internal const val BINDER_CONFIRM_TIMEOUT_MS = 6_000L
        internal const val BINDER_CONFIRM_POLL_MS = 300L
        internal const val MIN_RETRY_MS = 8_000L
        internal const val MAX_RETRY_MS = 60_000L
        internal const val AUTO_ENABLE_COOLDOWN_MS = 60_000L
        internal const val NETWORK_TRANSITION_GRACE_MS = 5_000L
        internal const val EVENT_DEBOUNCE_MS = 1_500L
    }
}

/** Process-level watchdog that keeps the service aligned with the persisted desired state. */
object NightDogRecovery {

    private const val TAG = "NightDogRecovery"
    private const val PREFS_NAME = "nightdog_recovery"
    private const val KEY_DESIRED_RUNNING = "desired_running"

    enum class Stage {
        IDLE,
        CHECKING_BINDER,
        DISCOVERING_ADB,
        WAITING_FOR_ADB,
        STARTING_SERVICE,
        RUNNING,
        MANUALLY_STOPPED,
        ERROR
    }

    enum class FailureCategory {
        NONE,
        WIFI_DISCONNECTED,
        WIRELESS_DEBUGGING_DISABLED,
        ENDPOINT_ABSENT,
        OFFLINE,
        AUTH_FAILED,
        SHELL_UNAVAILABLE,
        BINDER_NOT_CONFIRMED,
        UNKNOWN
    }

    enum class ActionHint {
        NONE,
        OPEN_WIRELESS_DEBUGGING,
        OPEN_PAIRING,
        CONNECT_WIFI,
        OPEN_BATTERY_SETTINGS
    }

    data class Snapshot(
        val stage: Stage = Stage.IDLE,
        val desiredRunning: Boolean = true,
        val binderAlive: Boolean = false,
        val transport: String? = null,
        val endpoint: String? = null,
        val failedAttempts: Int = 0,
        val lastResult: String = "Sin comprobaciones todavía",
        val lastAttemptElapsedRealtime: Long = 0L,
        val runningSinceElapsedRealtime: Long = 0L,
        val serverPid: Int? = null,
        val recoveryCount: Int = 0,
        val lastBinderLostElapsedRealtime: Long = 0L,
        val lastRecoveryElapsedRealtime: Long = 0L,
        val failureCategory: FailureCategory = FailureCategory.NONE,
        val actionHint: ActionHint = ActionHint.NONE,
        val wifiConnected: Boolean? = null,
        val wirelessAutoRestoreAttempted: Boolean = false,
        val batteryOptimizationIgnored: Boolean? = null,
        val recoveryTimeline: List<String> = emptyList()
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var applicationContext: Context? = null
    @Volatile private var listenersRegistered = false
    @Volatile private var systemObserversRegistered = false

    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var adbWifiObserver: ContentObserver? = null
    private var deviceResumeReceiver: BroadcastReceiver? = null

    private val systemEnv = object : NightDogSystemEnvironment {
        override fun isWifiConnected(): Boolean =
            applicationContext?.let { AdbTransportResolver.isWifiConnected(it) } ?: false

        override fun hasLocalPersistentTcpEndpoint(): Boolean =
            AdbTransportResolver.localPersistentTcpEndpoint() != null

        override fun isAutoRestoreWirelessEnabled(): Boolean = runCatching {
            ShizukuSettings.getPreferences() != null && ShizukuSettings.isAutoRestoreWirelessAdbEnabled()
        }.getOrDefault(false)

        override fun tryEnableWirelessDebugging(): WirelessDebuggingRestoreResult {
            val ctx = applicationContext ?: return WirelessDebuggingRestoreResult.NotPermitted
            return AdbTransportResolver.tryEnableWirelessDebugging(ctx)
        }

        override fun isIgnoringBatteryOptimizations(): Boolean? =
            applicationContext?.let { AdbTransportResolver.isIgnoringBatteryOptimizations(it) }

        override fun onServiceConfirmedRunning() {
            runCatching {
                if (ShizukuSettings.getPreferences() != null && Shizuku.pingBinder()) {
                    val mode = if (Shizuku.getUid() == 0) {
                        ShizukuSettings.LaunchMethod.ROOT
                    } else {
                        ShizukuSettings.LaunchMethod.ADB
                    }
                    ShizukuSettings.setLastLaunchMode(mode)
                }
            }
        }
    }

    private val schedulerPort = AndroidNightDogSchedulerPort { applicationContext }

    private val engine = NightDogRecoveryEngine(
        scope = scope,
        clockElapsedMs = SystemClock::elapsedRealtime,
        delayMs = { delay(it) },
        isDesiredRunning = { isDesiredRunning() },
        setDesiredRunning = { desired ->
            applicationContext?.let { context ->
                if (isUserUnlocked(context)) {
                    preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, desired).apply()
                }
            }
        },
        pingBinder = { Shizuku.pingBinder() },
        discoverEndpoint = {
            val context = applicationContext ?: return@NightDogRecoveryEngine AdbDiscoveryResult.Absent
            AdbTransportResolver.discoverDetailed(context)
        },
        openSession = { endpoint -> RealRecoveryAdbSession(endpoint) },
        discoverAlternateEndpoint = { previous ->
            val context = applicationContext
            if (context == null) null else AdbTransportResolver.alternateLocalEndpoint(context, previous)
        },
        isWirelessDebuggingDisabled = {
            applicationContext?.let { AdbTransportResolver.wirelessDebuggingState(it) == 0 } ?: false
        },
        systemEnv = systemEnv,
        scheduler = schedulerPort
    )

    val snapshot: StateFlow<Snapshot> = engine.snapshot

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        engine.onBinderReceived()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        engine.onBinderDead()
    }

    @Synchronized
    fun start(context: Context) {
        if (!isUserUnlocked(context)) return
        val appCtx = context.applicationContext
        applicationContext = appCtx
        ensureDesiredStateInitialized(appCtx)
        engine.start()
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
        }
        registerSystemObserversLocked(appCtx)
    }

    internal suspend fun awaitActiveRecovery(timeoutMs: Long) {
        engine.awaitActiveRecovery(timeoutMs)
    }

    @Synchronized
    fun onBootOrUpgrade(context: Context, action: String?) {
        if (!isUserUnlocked(context)) return
        start(context)
        engine.onBootUnlocked()
    }

    @Synchronized
    fun onScheduledWakeup(context: Context, jobId: Int) {
        if (!isUserUnlocked(context)) return
        start(context)
        val reason = when (jobId) {
            NightDogJobScheduler.JOB_ID_NETWORK_RECOVERY -> "red disponible"
            NightDogJobScheduler.JOB_ID_WIRELESS_SETTING_TRIGGER -> "ajuste adb_wifi_enabled"
            NightDogJobScheduler.JOB_ID_PERIODIC_HEALTH -> "vigilancia periódica"
            else -> "job#$jobId"
        }
        engine.onScheduledWakeup(reason)
    }

    @Synchronized
    fun onBinderRequestWhileDead(context: Context) {
        if (!isUserUnlocked(context)) return
        start(context)
        engine.onScheduledWakeup("solicitud rish/binder")
    }

    @Synchronized
    fun requestManualStart(context: Context, recoverImmediately: Boolean = true) {
        if (!isUserUnlocked(context)) return
        applicationContext = context.applicationContext
        engine.requestManualStart(recoverImmediately)
    }

    @Synchronized
    fun prepareForManualStop(context: Context) {
        if (!isUserUnlocked(context)) return
        applicationContext = context.applicationContext
        engine.prepareForManualStop()
    }

    fun prepareForManualStop() {
        val context = applicationContext ?: return
        prepareForManualStop(context)
    }

    fun isDesiredRunning(context: Context? = applicationContext): Boolean {
        val resolvedContext = context ?: return false
        if (!isUserUnlocked(resolvedContext)) return false
        return preferences(resolvedContext).getBoolean(KEY_DESIRED_RUNNING, true)
    }

    @Synchronized
    fun stop() {
        if (listenersRegistered) {
            Shizuku.removeBinderReceivedListener(binderReceivedListener)
            Shizuku.removeBinderDeadListener(binderDeadListener)
            listenersRegistered = false
        }
        unregisterSystemObserversLocked()
        engine.stop()
        applicationContext = null
    }

    private fun registerSystemObserversLocked(context: Context) {
        if (systemObserversRegistered) return
        systemObserversRegistered = true

        // 1. Observador de conectividad Wi-Fi / cambios de AP / cambios de IP
        runCatching {
            val cm = context.getSystemService(ConnectivityManager::class.java)
            if (cm != null) {
                val callback = object : ConnectivityManager.NetworkCallback() {
                    override fun onAvailable(network: Network) {
                        dispatchNetworkSnapshot(context)
                    }

                    override fun onLost(network: Network) {
                        dispatchNetworkSnapshot(context)
                    }

                    override fun onLinkPropertiesChanged(network: Network, linkProperties: LinkProperties) {
                        dispatchNetworkSnapshot(context)
                    }

                    override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                        dispatchNetworkSnapshot(context)
                    }
                }
                val request = NetworkRequest.Builder()
                    .addTransportType(NetworkCapabilities.TRANSPORT_WIFI)
                    .build()
                cm.registerNetworkCallback(request, callback)
                networkCallback = callback
            }
        }.onFailure { Log.w(TAG, "No se pudo registrar NetworkCallback Wi-Fi", it) }

        // 2. ContentObserver de Settings.Global.adb_wifi_enabled
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            runCatching {
                val uri = Settings.Global.getUriFor(AdbTransportResolver.ADB_WIFI_ENABLED)
                if (uri != null) {
                    val observer = object : ContentObserver(Handler(Looper.getMainLooper())) {
                        override fun onChange(selfChange: Boolean) {
                            val state = AdbTransportResolver.wirelessDebuggingState(context)
                            if (state == 0 || state == 1) {
                                engine.onWirelessDebuggingSettingChanged(enabled = state == 1)
                            }
                        }
                    }
                    context.contentResolver.registerContentObserver(uri, false, observer)
                    adbWifiObserver = observer
                }
            }.onFailure { Log.w(TAG, "No se pudo registrar ContentObserver de adb_wifi_enabled", it) }
        }

        // 3. Reanudación tras suspensión / Doze / desbloqueo
        runCatching {
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    when (intent.action) {
                        Intent.ACTION_USER_PRESENT -> engine.onDeviceResume()
                        PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED -> {
                            val pm = ctx.getSystemService(PowerManager::class.java)
                            if (pm != null && !pm.isDeviceIdleMode) {
                                engine.onDeviceResume()
                            }
                        }
                    }
                }
            }
            val filter = IntentFilter().apply {
                addAction(Intent.ACTION_USER_PRESENT)
                addAction(PowerManager.ACTION_DEVICE_IDLE_MODE_CHANGED)
            }
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                context.registerReceiver(receiver, filter, Context.RECEIVER_NOT_EXPORTED)
            } else {
                context.registerReceiver(receiver, filter)
            }
            deviceResumeReceiver = receiver
        }.onFailure { Log.w(TAG, "No se pudo registrar receptor de reanudación", it) }
    }

    private fun unregisterSystemObserversLocked() {
        if (!systemObserversRegistered) return
        systemObserversRegistered = false
        val context = applicationContext
        networkCallback?.let { cb ->
            runCatching {
                context?.getSystemService(ConnectivityManager::class.java)?.unregisterNetworkCallback(cb)
            }
        }
        networkCallback = null

        adbWifiObserver?.let { obs ->
            runCatching { context?.contentResolver?.unregisterContentObserver(obs) }
        }
        adbWifiObserver = null

        deviceResumeReceiver?.let { rcv ->
            runCatching { context?.unregisterReceiver(rcv) }
        }
        deviceResumeReceiver = null
    }

    private fun dispatchNetworkSnapshot(context: Context) {
        val wifi = AdbTransportResolver.isWifiConnected(context)
        val fp = AdbTransportResolver.currentNetworkFingerprint(context)
        engine.onNetworkChanged(wifiConnected = wifi, networkFingerprint = fp)
    }

    private fun isUserUnlocked(context: Context): Boolean =
        runCatching { context.getSystemService(UserManager::class.java)?.isUserUnlocked ?: true }
            .getOrDefault(false)

    private fun ensureDesiredStateInitialized(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_DESIRED_RUNNING)) {
            prefs.edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private class RealRecoveryAdbSession(endpoint: AdbEndpoint) : RecoveryAdbSession {
        private val client = AdbClient(
            host = endpoint.host,
            port = endpoint.port,
            key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku"),
            requireTls = endpoint.requireTls,
            allowPublicKeyPrompt = false
        )

        override fun connect() = client.connect()

        override fun verifyShell(): Boolean = client.verifyShell()

        override fun startServer() {
            client.shellCommand(Starter.internalCommand, null)
        }

        override fun queryServerPid(): Int? {
            val output = ByteArrayOutputStream()
            client.shellCommand("pidof shizuku_server", output::write)
            return output.toString(Charsets.UTF_8.name())
                .trim()
                .split(Regex("\\s+"))
                .firstOrNull()
                ?.toIntOrNull()
        }

        override fun close() = client.close()
    }
}
