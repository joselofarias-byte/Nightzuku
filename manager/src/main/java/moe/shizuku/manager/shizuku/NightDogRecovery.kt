package moe.shizuku.manager.shizuku

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
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

internal class NightDogRecoveryEngine(
    private val scope: CoroutineScope,
    private val clockElapsedMs: () -> Long,
    private val delayMs: suspend (Long) -> Unit,
    private val isDesiredRunning: () -> Boolean,
    private val setDesiredRunning: (Boolean) -> Unit,
    private val pingBinder: () -> Boolean,
    private val discoverEndpoint: suspend () -> AdbDiscoveryResult,
    private val openSession: (AdbEndpoint) -> RecoveryAdbSession,
    private val logWarning: (String) -> Unit = { Log.w(TAG, it) },
    private val logDebug: (String) -> Unit = { Log.d(TAG, it) },
    private val pollIntervalMs: Long = POLL_INTERVAL_MS,
    private val recoverySettleMs: Long = RECOVERY_SETTLE_MS,
    private val binderConfirmTimeoutMs: Long = BINDER_CONFIRM_TIMEOUT_MS,
    private val binderConfirmPollMs: Long = BINDER_CONFIRM_POLL_MS,
    private val minRetryMs: Long = MIN_RETRY_MS,
    private val maxRetryMs: Long = MAX_RETRY_MS
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

    private val _snapshot = MutableStateFlow(NightDogRecovery.Snapshot())
    val snapshot: StateFlow<NightDogRecovery.Snapshot> = _snapshot.asStateFlow()

    fun retryDelayMs(attempts: Int = failedAttempts): Long {
        if (attempts <= 0) return 0L
        val shift = (attempts - 1).coerceIn(0, 3)
        return (minRetryMs shl shift).coerceAtMost(maxRetryMs)
    }

    fun start(startPollingLoop: Boolean = true) {
        synchronized(lock) {
            if (started) return
            started = true
            if (startPollingLoop) {
                pollingJob = scope.launch {
                    while (isActive) {
                        pollOnce()
                        delayMs(pollIntervalMs)
                    }
                }
            }
            if (isDesiredRunning() && !pingBinder()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.CHECKING_BINDER,
                    result = "Inicio de la app: servicio deseado activo; comprobando ADB",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
                requestRecoveryLocked()
            } else if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada",
                    binderAlive = pingBinder(),
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
            }
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
        synchronized(lock) {
            if (!started) return
            if (!isDesiredRunning()) {
                publishLocked(
                    stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                    result = "Detenido manualmente; recuperación deshabilitada",
                    binderAlive = pingBinder(),
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
                return
            }
            activeAttemptId = ++attemptSequence
            recoveryJob?.cancel()
            recoveryJob = null
            recToClose = recoverySession
            recoverySession = null
            if (markRunningLocked("Binder recibido; servicio activo")) {
                shouldRefreshPid = true
            }
        }
        runCatching { recToClose?.close() }
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
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
            } else {
                if (recoveryJob?.isActive != true) {
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Binder perdido; iniciando recuperación",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.NONE
                    )
                }
                requestRecoveryLocked()
            }
        }
        runCatching { pidToClose?.close() }
    }

    fun requestManualStart(recoverImmediately: Boolean = true) {
        synchronized(lock) {
            setDesiredRunning(true)
            failedAttempts = 0
            lastAttemptAt = 0L
            if (pingBinder()) {
                markRunningLocked("Binder ya está activo")
            } else {
                publishLocked(
                    stage = NightDogRecovery.Stage.CHECKING_BINDER,
                    result = "Inicio solicitado; comprobando Binder y ADB",
                    binderAlive = false,
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
                if (recoverImmediately && started) {
                    requestRecoveryLocked()
                }
            }
        }
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
            publishLocked(
                stage = NightDogRecovery.Stage.MANUALLY_STOPPED,
                result = "Detenido manualmente; recuperación deshabilitada",
                binderAlive = pingBinder(),
                transport = null,
                endpoint = null,
                failureCategory = NightDogRecovery.FailureCategory.NONE
            )
        }
        runCatching { recToClose?.close() }
        runCatching { pidToClose?.close() }
    }

    fun pollOnce() {
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
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
            } else if (alive) {
                markRunningLocked("Binder responde correctamente")
            } else {
                if (_snapshot.value.binderAlive) {
                    lastBinderLostAt = clockElapsedMs()
                    runningSinceAt = 0L
                    currentServerPid = null
                    publishLocked(
                        stage = NightDogRecovery.Stage.CHECKING_BINDER,
                        result = "Binder no responde; buscando transporte ADB",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.NONE
                    )
                }
                if (recoveryJob?.isActive != true) {
                    requestRecoveryLocked()
                }
            }
        }
    }

    internal fun activeRecoveryJobForTest(): Job? = synchronized(lock) { recoveryJob }

    suspend fun awaitActiveRecovery(timeoutMs: Long) {
        val job = synchronized(lock) { recoveryJob } ?: return
        withTimeoutOrNull(timeoutMs) {
            job.join()
        }
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
        publishLocked(
            stage = NightDogRecovery.Stage.RUNNING,
            result = result,
            binderAlive = true,
            transport = "Binder activo",
            endpoint = "no requerido",
            failureCategory = NightDogRecovery.FailureCategory.NONE
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
                failureCategory = NightDogRecovery.FailureCategory.NONE
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

        recoveryJob = scope.launch {
            runRecoveryAttempt(attemptId)
        }
    }

    private suspend fun runRecoveryAttempt(attemptId: Long) {
        var currentEndpoint: AdbEndpoint? = null
        try {
            if (recoverySettleMs > 0L) {
                delayMs(recoverySettleMs)
            }
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder ya está activo")
                    return
                }
                failedAttempts++
                lastAttemptAt = clockElapsedMs()
                publishLocked(
                    stage = NightDogRecovery.Stage.DISCOVERING_ADB,
                    result = "Buscando endpoint ADB local actual; aún no autenticado",
                    binderAlive = false,
                    transport = null,
                    endpoint = null,
                    failureCategory = NightDogRecovery.FailureCategory.NONE
                )
            }

            val discovery = discoverEndpoint()
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder ya está activo")
                    return
                }
                when (discovery) {
                    is AdbDiscoveryResult.WirelessDebuggingDisabled -> {
                        lastAttemptAt = clockElapsedMs()
                        publishLocked(
                            stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                            result = "Depuración inalámbrica desactivada por Android o el usuario. Actívala en Opciones de desarrollador",
                            binderAlive = false,
                            transport = "desactivado",
                            endpoint = null,
                            failureCategory = NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED
                        )
                        return
                    }
                    is AdbDiscoveryResult.Absent -> {
                        lastAttemptAt = clockElapsedMs()
                        publishLocked(
                            stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                            result = "Endpoint ADB local ausente. Comprueba Wi-Fi, depuración inalámbrica y emparejamiento",
                            binderAlive = false,
                            transport = "ninguno",
                            endpoint = null,
                            failureCategory = NightDogRecovery.FailureCategory.ENDPOINT_ABSENT
                        )
                        return
                    }
                    is AdbDiscoveryResult.Found -> {
                        currentEndpoint = discovery.endpoint
                    }
                }
            }

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
                session.connect()
                synchronized(lock) {
                    if (!isCurrentAttemptLocked(attemptId)) return
                    if (pingBinder()) {
                        markRunningLocked("Binder ya está activo")
                        return
                    }
                }
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
                        failureCategory = NightDogRecovery.FailureCategory.NONE
                    )
                }
                session.startServer()
            } finally {
                synchronized(lock) {
                    if (recoverySession === session) recoverySession = null
                }
                runCatching { session.close() }
            }

            val confirmed = awaitBinderConfirmation(attemptId)
            var shouldRefreshPid = false
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (confirmed && pingBinder()) {
                    shouldRefreshPid = markRunningLocked("Binder verificado tras la recuperación")
                } else {
                    lastAttemptAt = clockElapsedMs()
                    publishLocked(
                        stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                        result = "Inicio enviado por ADB, pero Binder aún no responde; se reintentará",
                        binderAlive = false,
                        failureCategory = NightDogRecovery.FailureCategory.BINDER_NOT_CONFIRMED
                    )
                }
            }
            if (shouldRefreshPid) refreshServerPid()
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            synchronized(lock) {
                if (!isCurrentAttemptLocked(attemptId)) return
                if (pingBinder()) {
                    markRunningLocked("Binder responde correctamente")
                    return
                }
                lastAttemptAt = clockElapsedMs()
                val category = AdbFailureClassifier.classify(error)
                val endpointText = currentEndpoint?.let { "${it.host}:${it.port}" }
                logWarning("Recuperación fallida ($category): ${error.javaClass.simpleName}")
                when (category) {
                    NightDogRecovery.FailureCategory.AUTH_FAILED -> publishLocked(
                        stage = NightDogRecovery.Stage.ERROR,
                        result = "Fallo de autorización o emparejamiento ADB revocado. Vuelve a emparejar desde Nightzuku",
                        binderAlive = false,
                        endpoint = endpointText,
                        failureCategory = category
                    )
                    NightDogRecovery.FailureCategory.OFFLINE -> publishLocked(
                        stage = NightDogRecovery.Stage.WAITING_FOR_ADB,
                        result = if (endpointText != null) {
                            "Endpoint ADB offline o inaccesible ($endpointText). Se reintentará"
                        } else {
                            "Endpoint ADB offline o inaccesible. Se reintentará"
                        },
                        binderAlive = false,
                        endpoint = endpointText,
                        failureCategory = category
                    )
                    NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE -> publishLocked(
                        stage = NightDogRecovery.Stage.ERROR,
                        result = "ADB conectado, pero el transporte no permitió ejecutar shell. Se reintentará",
                        binderAlive = false,
                        endpoint = endpointText,
                        failureCategory = category
                    )
                    else -> publishLocked(
                        stage = NightDogRecovery.Stage.ERROR,
                        result = "Falló la recuperación ADB (${error.javaClass.simpleName}). Se reintentará",
                        binderAlive = false,
                        endpoint = endpointText,
                        failureCategory = category
                    )
                }
            }
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
            // ponytail: PID is diagnostic only; discover once per Binder attachment, not every poll.
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
            // ponytail: ADB diagnostics must not downgrade a healthy Binder.
            logDebug("PID no disponible: ${error.javaClass.simpleName}")
        } finally {
            synchronized(lock) {
                if (activePidId == pidId) {
                    pidJob = null
                }
            }
        }
    }

    private fun publishLocked(
        stage: NightDogRecovery.Stage,
        result: String,
        binderAlive: Boolean = pingBinder(),
        transport: String? = _snapshot.value.transport,
        endpoint: String? = _snapshot.value.endpoint,
        failureCategory: NightDogRecovery.FailureCategory = _snapshot.value.failureCategory
    ) {
        if (!started && stage != NightDogRecovery.Stage.MANUALLY_STOPPED) return
        val desired = isDesiredRunning()
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
            failureCategory = if (desired) failureCategory else NightDogRecovery.FailureCategory.NONE
        )
    }

    companion object {
        private const val TAG = "NightDogRecovery"
        internal const val POLL_INTERVAL_MS = 4_000L
        internal const val RECOVERY_SETTLE_MS = 1_500L
        internal const val BINDER_CONFIRM_TIMEOUT_MS = 6_000L
        internal const val BINDER_CONFIRM_POLL_MS = 300L
        internal const val MIN_RETRY_MS = 8_000L
        internal const val MAX_RETRY_MS = 60_000L
    }
}

/** Process-level watchdog that keeps the service aligned with the persisted desired state. */
object NightDogRecovery {

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
        WIRELESS_DEBUGGING_DISABLED,
        ENDPOINT_ABSENT,
        OFFLINE,
        AUTH_FAILED,
        SHELL_UNAVAILABLE,
        BINDER_NOT_CONFIRMED,
        UNKNOWN
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
        val failureCategory: FailureCategory = FailureCategory.NONE
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var applicationContext: Context? = null
    @Volatile private var listenersRegistered = false

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
        openSession = { endpoint -> RealRecoveryAdbSession(endpoint) }
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
        applicationContext = context.applicationContext
        ensureDesiredStateInitialized(context)
        engine.start()
        if (!listenersRegistered) {
            Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
            Shizuku.addBinderDeadListener(binderDeadListener)
            listenersRegistered = true
        }
    }

    internal suspend fun awaitActiveRecovery(timeoutMs: Long) {
        engine.awaitActiveRecovery(timeoutMs)
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
        engine.stop()
        applicationContext = null
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
