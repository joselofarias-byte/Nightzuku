package moe.shizuku.manager.shizuku

import android.content.Context
import android.content.Intent
import android.os.SystemClock
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/**
 * Process-level watchdog that keeps the Shizuku service aligned with the user's persisted
 * desired state.
 *
 * Any unexpected Binder loss is recoverable while [isDesiredRunning] is true. Recovery stops
 * only after the user explicitly requests a manual stop. Attempts continue indefinitely with
 * bounded backoff so temporary ADB, network or developer-option changes cannot permanently
 * disable recovery.
 */
object NightDogRecovery {

    private const val PREFS_NAME = "nightdog_recovery"
    private const val KEY_DESIRED_RUNNING = "desired_running"

    private const val POLL_INTERVAL_MS = 4_000L
    private const val RECOVERY_SETTLE_MS = 1_500L
    private const val MIN_RETRY_MS = 8_000L
    private const val MAX_RETRY_MS = 60_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var recoveryJob: Job? = null

    @Volatile
    private var failedAttempts = 0

    @Volatile
    private var lastAttemptAt = 0L

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        applicationContext?.let { context ->
            preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
        failedAttempts = 0
        lastAttemptAt = 0L
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        requestRecovery()
    }

    @Synchronized
    fun start(context: Context) {
        applicationContext = context.applicationContext
        ensureDesiredStateInitialized(context)
        if (pollingJob?.isActive == true) return

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        pollingJob = scope.launch {
            while (isActive) {
                if (Shizuku.pingBinder()) {
                    failedAttempts = 0
                    lastAttemptAt = 0L
                } else if (isDesiredRunning()) {
                    requestRecovery()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /** Records an explicit user request to keep the service running. */
    @Synchronized
    fun requestManualStart(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        failedAttempts = 0
        lastAttemptAt = 0L
        if (!Shizuku.pingBinder()) requestRecovery()
    }

    /**
     * Records the only condition that disables automatic recovery: an explicit manual stop.
     */
    @Synchronized
    fun prepareForManualStop(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, false).apply()
        recoveryJob?.cancel()
        recoveryJob = null
        failedAttempts = 0
        lastAttemptAt = 0L
    }

    /** Compatibility entry point used by Settings after the watchdog has been started. */
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
        applicationContext = null
        failedAttempts = 0
        lastAttemptAt = 0L
    }

    private fun ensureDesiredStateInitialized(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_DESIRED_RUNNING)) {
            // Existing installations historically behaved as "keep running unless stopped".
            prefs.edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun retryDelayMs(): Long {
        if (failedAttempts <= 0) return 0L
        val shift = (failedAttempts - 1).coerceAtMost(3)
        return (MIN_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    private fun requestRecovery() {
        if (Shizuku.pingBinder()) return
        if (!isDesiredRunning()) return
        if (recoveryJob?.isActive == true) return

        val now = SystemClock.elapsedRealtime()
        val retryDelay = retryDelayMs()
        if (lastAttemptAt != 0L && now - lastAttemptAt < retryDelay) return

        recoveryJob = scope.launch {
            delay(RECOVERY_SETTLE_MS)
            if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch

            val context = applicationContext ?: return@launch
            val port = EnvironmentUtils.getAdbTcpPort()

            failedAttempts++
            lastAttemptAt = SystemClock.elapsedRealtime()

            if (port <= 0) return@launch

            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, "127.0.0.1")
                putExtra(StarterActivity.EXTRA_PORT, port)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            runCatching {
                context.startActivity(intent)
            }
        }
    }
}
