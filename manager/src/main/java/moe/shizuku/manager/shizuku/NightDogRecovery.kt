package moe.shizuku.manager.shizuku

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Observer
import java.io.ByteArrayOutputStream
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
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbTcpProbe
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.persistence.NightDogBackoff
import moe.shizuku.manager.persistence.RecoveryTransport
import moe.shizuku.manager.persistence.RecoveryTransportPolicy
import moe.shizuku.manager.persistence.TransportCandidate
import moe.shizuku.manager.starter.StarterActivity
import rikka.shizuku.Shizuku

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

    data class Snapshot(
        val stage: Stage = Stage.IDLE,
        val desiredRunning: Boolean = true,
        val binderAlive: Boolean = false,
        val transport: String? = null,
        val transportKind: RecoveryTransport = RecoveryTransport.NONE,
        val endpoint: String? = null,
        val failedAttempts: Int = 0,
        val lastResult: String = "No checks yet",
        val lastResultKey: String = RESULT_NO_CHECKS,
        val lastFailure: String? = null,
        val lastAttemptElapsedRealtime: Long = 0L,
        val nextRetryElapsedRealtime: Long = 0L,
        val runningSinceElapsedRealtime: Long = 0L,
        val serverPid: Int? = null,
        val recoveryCount: Int = 0,
        val lastBinderLostElapsedRealtime: Long = 0L,
        val lastRecoveryElapsedRealtime: Long = 0L,
        val reactivationRequired: Boolean = false
    )

    const val RESULT_NO_CHECKS = "no_checks"
    const val RESULT_BINDER_RECEIVED = "binder_received"
    const val RESULT_BINDER_RESPONDING = "binder_responding"
    const val RESULT_BINDER_ALREADY_ALIVE = "binder_already_alive"
    const val RESULT_BINDER_LOST = "binder_lost"
    const val RESULT_BINDER_UNRESPONSIVE = "binder_unresponsive"
    const val RESULT_MANUALLY_STOPPED = "manually_stopped"
    const val RESULT_RECOVERY_SKIPPED = "recovery_skipped_manual_stop"
    const val RESULT_WATCHDOG_STOPPED = "watchdog_stopped"
    const val RESULT_MDNS_FOUND = "mdns_found"
    const val RESULT_DISCOVERING = "discovering"
    const val RESULT_START_REQUESTED = "start_requested"
    const val RESULT_NO_USABLE_ENDPOINT = "no_usable_endpoint"
    const val RESULT_STARTER_FAILED = "starter_failed"
    const val RESULT_WAITING_RETRY = "waiting_retry"
    const val RESULT_APP_START = "app_start"
    const val RESULT_MANUAL_START = "manual_start"
    const val RESULT_RECOVER_NOW = "recover_now"

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var applicationContext: Context? = null
    @Volatile private var pollingJob: Job? = null
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var failedAttempts = 0
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var adbMdns: AdbMdns? = null
    @Volatile private var runningSinceAt = 0L
    @Volatile private var currentServerPid: Int? = null
    @Volatile private var recoveryCount = 0
    @Volatile private var lastBinderLostAt = 0L
    @Volatile private var lastRecoveryAt = 0L
    @Volatile private var lastFailure: String? = null

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val mdnsObserver = Observer<Int> { port ->
        if (port != null && port > 0) {
            publish(
                Stage.DISCOVERING_ADB,
                RESULT_MDNS_FOUND,
                "mDNS found an ADB endpoint; preparing start"
            )
            requestRecovery()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        applicationContext?.let { context ->
            preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
        ShizukuSettings.setAdbReactivationRequired(false)
        val now = SystemClock.elapsedRealtime()
        if (runningSinceAt == 0L) runningSinceAt = now
        if (lastBinderLostAt > 0L) {
            recoveryCount++
            lastRecoveryAt = now
        }
        failedAttempts = 0
        lastAttemptAt = 0L
        lastFailure = null
        recoveryJob?.cancel()
        recoveryJob = null
        publishRunning(RESULT_BINDER_RECEIVED, "Binder received; service is active")
        refreshServerPid()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        lastBinderLostAt = SystemClock.elapsedRealtime()
        runningSinceAt = 0L
        currentServerPid = null
        publish(
            Stage.CHECKING_BINDER,
            RESULT_BINDER_LOST,
            "Binder lost; starting recovery",
            binderAlive = false
        )
        requestRecovery()
    }

    @Synchronized
    fun start(context: Context) {
        applicationContext = context.applicationContext
        ensureDesiredStateInitialized(context)
        if (pollingJob?.isActive == true) return

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && adbMdns == null) {
            adbMdns = AdbMdns(applicationContext!!, AdbMdns.TLS_CONNECT, mdnsObserver).also { it.start() }
        }

        pollingJob = scope.launch {
            while (isActive) {
                val alive = Shizuku.pingBinder()
                if (alive) {
                    if (runningSinceAt == 0L) runningSinceAt = SystemClock.elapsedRealtime()
                    failedAttempts = 0
                    lastAttemptAt = 0L
                    lastFailure = null
                    publishRunning(RESULT_BINDER_RESPONDING, "Binder is responding")
                    if (currentServerPid == null) refreshServerPid()
                } else if (isDesiredRunning()) {
                    if (_snapshot.value.binderAlive) {
                        lastBinderLostAt = SystemClock.elapsedRealtime()
                        runningSinceAt = 0L
                        currentServerPid = null
                    }
                    publish(
                        Stage.CHECKING_BINDER,
                        RESULT_BINDER_UNRESPONSIVE,
                        "Binder is not responding; looking for an ADB transport",
                        binderAlive = false
                    )
                    requestRecovery()
                } else {
                    runningSinceAt = 0L
                    currentServerPid = null
                    publish(
                        Stage.MANUALLY_STOPPED,
                        RESULT_MANUALLY_STOPPED,
                        "Service was stopped manually",
                        binderAlive = false
                    )
                }
                delay(NightDogBackoff.POLL_INTERVAL_MS)
            }
        }

        if (isDesiredRunning(context) && !Shizuku.pingBinder()) {
            publish(
                Stage.CHECKING_BINDER,
                RESULT_APP_START,
                "App start: desired running is on; checking ADB",
                binderAlive = false
            )
            requestRecovery()
        }
    }

    @Synchronized
    fun requestManualStart(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        failedAttempts = 0
        lastAttemptAt = 0L
        publish(
            Stage.CHECKING_BINDER,
            RESULT_MANUAL_START,
            "Start requested; checking Binder and ADB"
        )
        if (!Shizuku.pingBinder()) requestRecovery()
    }

    @Synchronized
    fun requestImmediateRecovery(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        failedAttempts = 0
        lastAttemptAt = 0L
        recoveryJob?.cancel()
        recoveryJob = null
        publish(
            Stage.CHECKING_BINDER,
            RESULT_RECOVER_NOW,
            "Recover now; checking Binder and ADB immediately"
        )
        if (Shizuku.pingBinder()) {
            publishRunning(RESULT_BINDER_ALREADY_ALIVE, "Binder is already active")
            return
        }
        requestRecovery()
    }

    @Synchronized
    fun prepareForManualStop(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, false).apply()
        recoveryJob?.cancel()
        recoveryJob = null
        failedAttempts = 0
        lastAttemptAt = 0L
        runningSinceAt = 0L
        currentServerPid = null
        publish(
            Stage.MANUALLY_STOPPED,
            RESULT_MANUALLY_STOPPED,
            "Stopped manually; recovery disabled",
            binderAlive = false
        )
    }

    fun prepareForManualStop() {
        val context = applicationContext ?: return
        prepareForManualStop(context)
    }

    fun isDesiredRunning(context: Context? = applicationContext): Boolean {
        val resolvedContext = context ?: return true
        return preferences(resolvedContext).getBoolean(KEY_DESIRED_RUNNING, true)
    }

    @Synchronized
    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        pollingJob?.cancel()
        pollingJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) adbMdns?.stop()
        adbMdns = null
        applicationContext = null
        failedAttempts = 0
        lastAttemptAt = 0L
        runningSinceAt = 0L
        currentServerPid = null
        recoveryCount = 0
        lastBinderLostAt = 0L
        lastRecoveryAt = 0L
        lastFailure = null
        _snapshot.value = Snapshot(
            stage = Stage.IDLE,
            lastResult = "Watchdog stopped",
            lastResultKey = RESULT_WATCHDOG_STOPPED
        )
    }

    private fun ensureDesiredStateInitialized(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_DESIRED_RUNNING)) {
            prefs.edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun resolveCandidate(): TransportCandidate {
        val persistent = AdbTransportResolver.persistentTcpEndpoint()?.let { endpoint ->
            TransportCandidate(
                kind = RecoveryTransport.PERSISTENT_LOCAL_TCP,
                host = endpoint.host,
                port = endpoint.port,
                configured = true,
                socketReachable = AdbTcpProbe.isReachable(endpoint.host, endpoint.port)
            )
        }
        val mdns = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)?.let { endpoint ->
                TransportCandidate(
                    kind = RecoveryTransport.MDNS_WIRELESS_DEBUGGING,
                    host = endpoint.host,
                    port = endpoint.port,
                    configured = true,
                    socketReachable = true
                )
            }
        } else {
            null
        }
        val system = AdbTransportResolver.systemAdbTcpEndpoint()?.let { endpoint ->
            TransportCandidate(
                kind = RecoveryTransport.SYSTEM_ADB_TCP,
                host = endpoint.host,
                port = endpoint.port,
                configured = true,
                socketReachable = AdbTcpProbe.isReachable(endpoint.host, endpoint.port)
            )
        }
        return RecoveryTransportPolicy.selectForRecoveryAttempt(persistent, mdns, system)
    }

    private fun refreshServerPid() {
        if (!Shizuku.pingBinder()) return
        scope.launch {
            val candidate = resolveCandidate().takeIf { it.kind != RecoveryTransport.NONE } ?: return@launch
            val host = candidate.host ?: return@launch
            val port = candidate.port ?: return@launch
            val pid = runCatching {
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                val output = ByteArrayOutputStream()
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    client.shellCommand(
                        "pidof shizuku_server 2>/dev/null | cut -d' ' -f1",
                        output::write
                    )
                }
                output.toString(Charsets.UTF_8.name()).trim().lineSequence().firstOrNull()?.trim()?.toIntOrNull()
            }.getOrNull()

            if (pid != null && Shizuku.pingBinder()) {
                currentServerPid = pid
                publishRunning(RESULT_BINDER_RESPONDING, "Binder is responding")
            }
        }
    }

    private fun requestRecovery() {
        if (Shizuku.pingBinder()) {
            publishRunning(RESULT_BINDER_ALREADY_ALIVE, "Binder is already active")
            return
        }
        if (!isDesiredRunning()) {
            publish(
                Stage.MANUALLY_STOPPED,
                RESULT_RECOVERY_SKIPPED,
                "Recovery skipped: manual stop"
            )
            return
        }
        if (recoveryJob?.isActive == true) return

        val now = SystemClock.elapsedRealtime()
        val retryDelay = NightDogBackoff.retryDelayMs(failedAttempts)
        if (lastAttemptAt != 0L && now - lastAttemptAt < retryDelay) {
            val remainingSeconds = ((retryDelay - (now - lastAttemptAt)) / 1000).coerceAtLeast(1)
            publish(
                Stage.WAITING_FOR_ADB,
                RESULT_WAITING_RETRY,
                "Waiting ${remainingSeconds}s before the next attempt"
            )
            return
        }

        recoveryJob = scope.launch {
            delay(NightDogBackoff.RECOVERY_SETTLE_MS)
            if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch

            val context = applicationContext ?: return@launch
            publish(
                Stage.DISCOVERING_ADB,
                RESULT_DISCOVERING,
                "Binder absent; resolving persistent TCP, mDNS/TLS and local ADB"
            )

            val candidate = resolveCandidate()
            failedAttempts++
            lastAttemptAt = SystemClock.elapsedRealtime()

            if (candidate.kind == RecoveryTransport.NONE) {
                lastFailure = "No usable ADB endpoint"
                publish(
                    Stage.WAITING_FOR_ADB,
                    RESULT_NO_USABLE_ENDPOINT,
                    "No usable ADB endpoint. Automatic search will continue",
                    transportKind = RecoveryTransport.NONE,
                    endpoint = null
                )
                return@launch
            }

            val host = candidate.host!!
            val port = candidate.port!!
            publish(
                Stage.STARTING_SERVICE,
                RESULT_START_REQUESTED,
                "Endpoint available; starting service",
                transportKind = candidate.kind,
                endpoint = candidate.endpoint
            )

            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            runCatching {
                context.startActivity(intent)
            }.onFailure { error ->
                lastFailure = error.javaClass.simpleName
                publish(
                    Stage.ERROR,
                    RESULT_STARTER_FAILED,
                    "Could not open the starter: ${error.javaClass.simpleName}",
                    transportKind = candidate.kind,
                    endpoint = candidate.endpoint
                )
            }
        }
    }

    private fun publishRunning(resultKey: String, result: String) {
        val tcp = AdbTransportResolver.persistentTcpEndpoint()
        publish(
            Stage.RUNNING,
            resultKey,
            result,
            binderAlive = true,
            transportKind = RecoveryTransport.BINDER_ALIVE,
            endpoint = tcp?.let { "${it.host}:${it.port}" }
        )
    }

    private fun publish(
        stage: Stage,
        resultKey: String,
        result: String,
        binderAlive: Boolean = Shizuku.pingBinder(),
        transportKind: RecoveryTransport = _snapshot.value.transportKind,
        endpoint: String? = _snapshot.value.endpoint
    ) {
        val now = SystemClock.elapsedRealtime()
        _snapshot.value = Snapshot(
            stage = stage,
            desiredRunning = isDesiredRunning(),
            binderAlive = binderAlive,
            transport = transportKind.name,
            transportKind = transportKind,
            endpoint = endpoint,
            failedAttempts = failedAttempts,
            lastResult = result,
            lastResultKey = resultKey,
            lastFailure = lastFailure,
            lastAttemptElapsedRealtime = lastAttemptAt,
            nextRetryElapsedRealtime = NightDogBackoff.nextRetryElapsedRealtime(
                now,
                lastAttemptAt,
                failedAttempts
            ),
            runningSinceElapsedRealtime = runningSinceAt,
            serverPid = currentServerPid,
            recoveryCount = recoveryCount,
            lastBinderLostElapsedRealtime = lastBinderLostAt,
            lastRecoveryElapsedRealtime = lastRecoveryAt,
            reactivationRequired = runCatching { ShizukuSettings.isAdbReactivationRequired() }
                .getOrDefault(false)
        )
    }
}
