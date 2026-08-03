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
 * Process-level watchdog that attempts to recover the Shizuku service after its binder dies.
 *
 * Recovery deliberately reuses [StarterActivity], which is the same entry point used by the
 * successful manual wireless-ADB start flow. Attempts are bounded and cooled down to avoid
 * restart storms when ADB is unavailable or the saved endpoint is no longer usable.
 */
object NightDogRecovery {

    private const val POLL_INTERVAL_MS = 4_000L
    private const val RECOVERY_SETTLE_MS = 1_500L
    private const val RECOVERY_COOLDOWN_MS = 8_000L
    private const val MANUAL_STOP_SUPPRESSION_MS = 60_000L
    private const val MAX_CONSECUTIVE_ATTEMPTS = 5

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile
    private var applicationContext: Context? = null

    @Volatile
    private var pollingJob: Job? = null

    @Volatile
    private var recoveryJob: Job? = null

    @Volatile
    private var consecutiveAttempts = 0

    @Volatile
    private var lastAttemptAt = 0L

    @Volatile
    private var recoverySuppressedUntil = 0L

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        consecutiveAttempts = 0
        lastAttemptAt = 0L
        recoverySuppressedUntil = 0L
        recoveryJob?.cancel()
        recoveryJob = null
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        requestRecovery()
    }

    @Synchronized
    fun start(context: Context) {
        if (pollingJob?.isActive == true) return

        applicationContext = context.applicationContext
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        pollingJob = scope.launch {
            while (isActive) {
                if (Shizuku.pingBinder()) {
                    consecutiveAttempts = 0
                    lastAttemptAt = 0L
                } else {
                    requestRecovery()
                }
                delay(POLL_INTERVAL_MS)
            }
        }
    }

    /**
     * Prevents the watchdog from undoing an explicit user-requested shutdown.
     * The suppression is temporary so a later manual start can restore normal recovery.
     */
    @Synchronized
    fun prepareForManualStop() {
        recoverySuppressedUntil = SystemClock.elapsedRealtime() + MANUAL_STOP_SUPPRESSION_MS
        recoveryJob?.cancel()
        recoveryJob = null
        consecutiveAttempts = 0
        lastAttemptAt = 0L
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
        consecutiveAttempts = 0
        lastAttemptAt = 0L
        recoverySuppressedUntil = 0L
    }

    private fun requestRecovery() {
        if (Shizuku.pingBinder()) return
        if (SystemClock.elapsedRealtime() < recoverySuppressedUntil) return
        if (recoveryJob?.isActive == true) return
        if (consecutiveAttempts >= MAX_CONSECUTIVE_ATTEMPTS) return

        val now = SystemClock.elapsedRealtime()
        if (lastAttemptAt != 0L && now - lastAttemptAt < RECOVERY_COOLDOWN_MS) return

        recoveryJob = scope.launch {
            delay(RECOVERY_SETTLE_MS)
            if (Shizuku.pingBinder()) return@launch
            if (SystemClock.elapsedRealtime() < recoverySuppressedUntil) return@launch

            val context = applicationContext ?: return@launch
            val port = EnvironmentUtils.getAdbTcpPort()
            if (port <= 0) return@launch

            consecutiveAttempts++
            lastAttemptAt = SystemClock.elapsedRealtime()

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
