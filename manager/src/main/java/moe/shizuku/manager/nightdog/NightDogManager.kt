package moe.shizuku.manager.nightdog

import android.content.Context
import android.os.Build
import android.os.ParcelFileDescriptor
import android.os.Process
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.Logger.LOGGER
import moe.shizuku.server.IShizukuService
import rikka.shizuku.Shizuku
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.coroutines.resume

object NightDogManager {
    private const val MANUAL_STOP = "nightdog_manual_stop"
    private const val KEY_RECOVERY_COUNT = "nightdog_recovery_count"
    private const val KEY_LAST_RECOVERY_AT = "nightdog_last_recovery_at"
    private const val KEY_BOOT_START = "nightdog_boot_start"
    private const val TEST_RECOVERY_TIMEOUT_MS = 90_000L
    private const val OPERATION_TIMEOUT_MS = 120_000L
    private const val WAIT_FOR_BINDER_TIMEOUT_MS = 15_000L

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val initialized = AtomicBoolean()
    private val mutex = Mutex()
    private var operationJob: Job? = null
    private val recoveryPending = AtomicBoolean(false)
    private val testRunning = AtomicBoolean(false)
    private var testJob: Job? = null

    private val _uiState = MutableStateFlow(NightDogUiState())
    val uiState: StateFlow<NightDogUiState> = _uiState.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        val currentPhase = _uiState.value.phase
        val currentTrigger = _uiState.value.trigger
        val wasOperationActive = _uiState.value.isOperationInProgress
        LOGGER.i("NightDog binder received: phase=$currentPhase trigger=$currentTrigger operationActive=$wasOperationActive pid=${resolveServerPid()}")

        cancelOperationJob()

        if (wasOperationActive) {
            val pid = resolveServerPid()
            LOGGER.i("NightDog: completing pending operation as SUCCESS trigger=$currentTrigger pid=$pid")
            _uiState.value = _uiState.value.copy(
                phase = NightDogPhase.SUCCESS,
                trigger = currentTrigger,
                serverOnline = true,
                serverPid = pid,
                newPid = pid,
                progressMessage = "success",
                errorMessage = null
            )
            scheduleIdleReset()
        }

        onServerRestored()
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        LOGGER.w("NightDog: Binder died")
        if (!isManuallyStopped()) {
            recoveryPending.set(true)
            ensureRunning(NightDogTrigger.AUTO_RECOVERY)
        }
    }

    fun initialize(context: Context) {
        if (!initialized.compareAndSet(false, true)) return
        _uiState.value = _uiState.value.copy(
            recoveryCount = getRecoveryCount(),
            lastRecoveryAt = getLastRecoveryAt(),
            mode = ShizukuSettings.getLastLaunchMode(),
            serverOnline = Shizuku.pingBinder()
        )
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        if (!Shizuku.pingBinder()) {
            ensureRunning(NightDogTrigger.APP_START)
        }
    }

    // region Single entry point ---------------------------------------------------------------

    fun ensureRunning(trigger: NightDogTrigger) {
        if (isManuallyStopped()) {
            LOGGER.i("NightDog operation skipped: reason=manual_stop trigger=$trigger")
            return
        }
        if (ShizukuSettings.getLastLaunchMode() != ShizukuSettings.LaunchMethod.ADB) {
            LOGGER.i("NightDog operation skipped: reason=not_adb_mode trigger=$trigger")
            return
        }
        if (Shizuku.pingBinder()) {
            LOGGER.i("NightDog operation skipped: reason=already_running trigger=$trigger")
            completeAsSuccessIfPending(trigger)
            return
        }
        synchronized(this) {
            if (operationJob?.isActive == true) {
                LOGGER.i("NightDog operation skipped: reason=operation_in_progress trigger=$trigger")
                return
            }
            operationJob = scope.launch { runOperation(trigger) }
        }
    }

    private fun completeAsSuccessIfPending(trigger: NightDogTrigger) {
        val current = _uiState.value
        if (current.isOperationInProgress) {
            val pid = resolveServerPid()
            LOGGER.i("NightDog: binder alive, completing pending operation trigger=$trigger pid=$pid")
            _uiState.value = current.copy(
                phase = NightDogPhase.SUCCESS,
                serverOnline = true,
                serverPid = pid,
                newPid = pid,
                progressMessage = "success",
                errorMessage = null
            )
            cancelOperationJob()
            scheduleIdleReset()
        } else {
            _uiState.value = current.copy(serverOnline = true, phase = NightDogPhase.IDLE)
        }
    }

    private suspend fun runOperation(trigger: NightDogTrigger) {
        mutex.withLock {
            if (Shizuku.pingBinder()) {
                LOGGER.i("NightDog operation skipped: reason=already_running (mutex) trigger=$trigger")
                setOperationIdle()
                return
            }
            if (isManuallyStopped()) {
                LOGGER.i("NightDog operation skipped: reason=manual_stop (mutex) trigger=$trigger")
                setOperationIdle()
                return
            }

            val startTime = System.currentTimeMillis()
            LOGGER.i("NightDog operation requested: trigger=$trigger state=${_uiState.value.phase} manualStop=${isManuallyStopped()} serverOnline=${Shizuku.pingBinder()}")

            setPhase(NightDogPhase.CHECKING, trigger, "checking_server")
            if (Shizuku.pingBinder()) {
                onOperationSuccess(trigger, startTime, null)
                return
            }

            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
                _uiState.value = _uiState.value.copy(
                    phase = NightDogPhase.ERROR,
                    trigger = trigger,
                    errorMessage = "Android 11+ required for wireless ADB recovery"
                )
                return
            }

            val appContext = moe.shizuku.manager.application.applicationContext
            var delayMillis = 2_000L
            var attempt = 0

            val result = withTimeoutOrNull(OPERATION_TIMEOUT_MS) {
                while (!isManuallyStopped() && !Shizuku.pingBinder()) {
                    attempt++
                    try {
                        setPhase(NightDogPhase.WAITING_ADB, trigger, "discovering_adb", attempt = attempt)
                        val port = discoverAdbPort(appContext)
                        if (port == null) {
                            setPhase(NightDogPhase.WAITING_ADB, trigger, "adb_not_found", attempt = attempt)
                            delay(delayMillis)
                            delayMillis = (delayMillis * 2).coerceAtMost(60_000L)
                            continue
                        }

                        setPhase(NightDogPhase.STARTING, trigger, "starting_server", attempt = attempt)
                        startThroughAdb(port)

                        setPhase(NightDogPhase.WAITING_SERVER, trigger, "waiting_binder", attempt = attempt)
                        if (waitForBinder()) {
                            return@withTimeoutOrNull true
                        }
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        LOGGER.w(e, "NightDog: operation failed trigger=$trigger attempt=$attempt")
                        setPhase(NightDogPhase.RECOVERING, trigger, "retry_after_error", attempt = attempt)
                    }
                    delay(delayMillis)
                    delayMillis = (delayMillis * 2).coerceAtMost(60_000L)
                }
                Shizuku.pingBinder()
            }

            if (result == true || Shizuku.pingBinder()) {
                onOperationSuccess(trigger, startTime, null)
            } else if (isManuallyStopped()) {
                setOperationIdle()
            } else {
                LOGGER.w("NightDog operation timeout: trigger=$trigger attempts=$attempt")
                _uiState.value = _uiState.value.copy(
                    phase = NightDogPhase.ERROR,
                    trigger = trigger,
                    errorMessage = "Operation timed out after ${OPERATION_TIMEOUT_MS / 1000}s"
                )
            }
        }
    }

    private fun onOperationSuccess(trigger: NightDogTrigger, startTime: Long, oldPid: Int?) {
        val elapsed = System.currentTimeMillis() - startTime
        val pid = resolveServerPid()
        LOGGER.i("NightDog operation succeeded: trigger=$trigger pid=$pid elapsedMs=$elapsed")
        _uiState.value = _uiState.value.copy(
            phase = NightDogPhase.SUCCESS,
            trigger = trigger,
            serverOnline = true,
            serverPid = pid,
            newPid = pid,
            oldPid = oldPid,
            operationStartTime = startTime,
            progressMessage = "success",
            errorMessage = null
        )
        scheduleIdleReset()
    }

    private fun scheduleIdleReset() {
        scope.launch {
            delay(5_000L)
            if (_uiState.value.phase == NightDogPhase.SUCCESS) {
                setOperationIdle()
            }
        }
    }

    private fun setOperationIdle() {
        _uiState.value = _uiState.value.copy(
            phase = NightDogPhase.IDLE,
            trigger = null,
            progressMessage = null,
            operationStartTime = 0L,
            oldPid = null,
            newPid = null,
            attempt = 0,
            errorMessage = null
        )
    }

    private fun cancelOperationJob() {
        synchronized(this) {
            operationJob?.cancel()
            operationJob = null
        }
    }

    private fun setPhase(
        phase: NightDogPhase,
        trigger: NightDogTrigger? = null,
        message: String? = null,
        attempt: Int = 0
    ) {
        _uiState.value = _uiState.value.copy(
            phase = phase,
            trigger = trigger ?: _uiState.value.trigger,
            progressMessage = message,
            attempt = attempt,
            serverOnline = Shizuku.pingBinder()
        )
    }

    // endregion

    // region Manual stop -----------------------------------------------------------------------

    fun markManualStop() {
        val previousPhase = _uiState.value.phase
        LOGGER.i("NightDog manual stop: previousPhase=$previousPhase")
        ShizukuSettings.getPreferences().edit().putBoolean(MANUAL_STOP, true).apply()
        recoveryPending.set(false)
        cancelOperationJob()
        setOperationIdle()
    }

    fun markManualStart() {
        val binderAlive = Shizuku.pingBinder()
        val previousPhase = _uiState.value.phase
        LOGGER.i("NightDog manual start: binderAlive=$binderAlive previousPhase=$previousPhase")
        ShizukuSettings.getPreferences().edit().putBoolean(MANUAL_STOP, false).apply()
        if (binderAlive) {
            completeAsSuccessIfPending(NightDogTrigger.MANUAL_START)
            if (!_uiState.value.isOperationInProgress) {
                _uiState.value = _uiState.value.copy(
                    serverOnline = true,
                    serverPid = resolveServerPid(),
                    phase = NightDogPhase.SUCCESS,
                    trigger = NightDogTrigger.MANUAL_START,
                    progressMessage = "success"
                )
                scheduleIdleReset()
            }
        } else {
            ensureRunning(NightDogTrigger.MANUAL_START)
        }
    }

    fun isManuallyStopped() = ShizukuSettings.getPreferences().getBoolean(MANUAL_STOP, false)

    // endregion

    // region Boot preference -------------------------------------------------------------------

    fun isBootStartEnabled() = ShizukuSettings.getPreferences().getBoolean(KEY_BOOT_START, false)

    fun setBootStartEnabled(enabled: Boolean) {
        ShizukuSettings.getPreferences().edit().putBoolean(KEY_BOOT_START, enabled).apply()
    }

    // endregion

    // region ADB operations --------------------------------------------------------------------

    private suspend fun discoverAdbPort(context: Context): Int? = withContext(Dispatchers.Main.immediate) {
        withTimeoutOrNull(5_000L) {
            suspendCancellableCoroutine { continuation ->
                lateinit var mdns: AdbMdns
                mdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                    if (port > 0 && continuation.isActive) {
                        mdns.stop()
                        continuation.resume(port)
                    }
                }
                continuation.invokeOnCancellation { mdns.stop() }
                mdns.start()
            }
        }
    }

    private fun startThroughAdb(port: Int) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        val client = AdbClient("127.0.0.1", port, key)
        try {
            client.connect()
            client.shellCommand(Starter.internalCommand, null)
        } finally {
            client.close()
        }
    }

    private suspend fun waitForBinder(): Boolean {
        if (Shizuku.pingBinder()) {
            LOGGER.i("NightDog waitForBinder: binderAlreadyAlive=true")
            return true
        }
        LOGGER.i("NightDog waitForBinder enter: binderAlreadyAlive=false")
        val start = System.currentTimeMillis()
        val result = withTimeoutOrNull(WAIT_FOR_BINDER_TIMEOUT_MS) {
            while (!Shizuku.pingBinder()) {
                delay(500L)
            }
            true
        } ?: false
        val elapsed = System.currentTimeMillis() - start
        if (result) {
            LOGGER.i("NightDog waitForBinder success: elapsedMs=$elapsed pid=${resolveServerPid()}")
        } else {
            LOGGER.w("NightDog waitForBinder timeout: elapsedMs=$elapsed")
        }
        return result
    }

    // endregion

    // region Recovery statistics ---------------------------------------------------------------

    private fun getRecoveryCount() = ShizukuSettings.getPreferences().getInt(KEY_RECOVERY_COUNT, 0)

    private fun getLastRecoveryAt() = ShizukuSettings.getPreferences().getLong(KEY_LAST_RECOVERY_AT, 0L)

    private fun onServerRestored() {
        val mode = ShizukuSettings.getLastLaunchMode()
        if (recoveryPending.compareAndSet(true, false)) {
            if (Shizuku.pingBinder()) {
                val now = System.currentTimeMillis()
                val newCount = getRecoveryCount() + 1
                ShizukuSettings.getPreferences().edit()
                    .putInt(KEY_RECOVERY_COUNT, newCount)
                    .putLong(KEY_LAST_RECOVERY_AT, now)
                    .apply()
                LOGGER.i("NightDog: recovery confirmed, total=$newCount")
                _uiState.value = _uiState.value.copy(
                    serverOnline = true,
                    mode = mode,
                    recoveryCount = newCount,
                    lastRecoveryAt = now,
                    phase = NightDogPhase.SUCCESS,
                    trigger = NightDogTrigger.AUTO_RECOVERY,
                    progressMessage = "recovered",
                    newPid = resolveServerPid()
                )
                scheduleIdleReset()
                return
            }
        }
        _uiState.value = _uiState.value.copy(serverOnline = Shizuku.pingBinder(), mode = mode)
    }

    fun resetStats() {
        if (_uiState.value.isAnyBusy) {
            LOGGER.i("NightDog: resetStats blocked, operation in progress")
            return
        }
        ShizukuSettings.getPreferences().edit()
            .remove(KEY_RECOVERY_COUNT)
            .remove(KEY_LAST_RECOVERY_AT)
            .apply()
        _uiState.value = _uiState.value.copy(recoveryCount = 0, lastRecoveryAt = 0L)
    }

    // endregion

    // region Server identity -------------------------------------------------------------------

    private fun resolveServerPid(): Int? {
        return try {
            val binder = Shizuku.getBinder() ?: return null
            if (!Shizuku.pingBinder()) return null
            val service = IShizukuService.Stub.asInterface(binder)
            val remote = service.newProcess(arrayOf("sh", "-c", "echo \$PPID"), null, null)
            ParcelFileDescriptor.AutoCloseOutputStream(remote.getOutputStream()).close()
            val out = ParcelFileDescriptor.AutoCloseInputStream(remote.getInputStream())
                .bufferedReader().use { it.readText() }.trim()
            remote.waitForTimeout(5, TimeUnit.SECONDS.name)
            out.toIntOrNull()
        } catch (e: Throwable) {
            LOGGER.w(e, "NightDog: resolveServerPid failed")
            null
        }
    }

    fun isRecoveryTestAvailable(): Boolean =
        initialized.get() && ShizukuSettings.getLastLaunchMode() == ShizukuSettings.LaunchMethod.ADB

    fun refreshStatus() {
        scope.launch {
            val online = Shizuku.pingBinder()
            val pid = if (online) resolveServerPid() else null
            _uiState.value = _uiState.value.copy(
                serverOnline = online,
                mode = ShizukuSettings.getLastLaunchMode(),
                serverPid = pid,
                recoveryCount = getRecoveryCount(),
                lastRecoveryAt = getLastRecoveryAt()
            )
        }
    }

    // endregion

    // region Recovery test state machine -------------------------------------------------------

    fun requestTestConfirmation(): Boolean {
        if (testRunning.get() || _uiState.value.isAnyBusy) return false
        if (!isRecoveryTestAvailable()) {
            setTestPhase(NightDogTestPhase.UNAVAILABLE, message = "unavailable_mode")
            return false
        }
        if (!Shizuku.pingBinder()) {
            setTestPhase(NightDogTestPhase.UNAVAILABLE, message = "server_offline")
            return false
        }
        val pid = resolveServerPid()
        if (pid == null || pid <= 1 || pid == Process.myPid()) {
            setTestPhase(NightDogTestPhase.ERROR, message = "pid_unresolved")
            return false
        }
        _uiState.value = _uiState.value.copy(
            serverOnline = true,
            serverPid = pid,
            testOldPid = pid,
            testNewPid = null,
            testElapsedMs = null,
            testMessage = null
        )
        return true
    }

    fun cancelTest() {
        if (testRunning.get()) return
        setTestPhase(NightDogTestPhase.IDLE)
    }

    fun confirmKillAndObserve() {
        if (!testRunning.compareAndSet(false, true)) return
        if (_uiState.value.isOperationInProgress) {
            testRunning.set(false)
            return
        }
        setTestPhase(NightDogTestPhase.STOPPING)
        testJob = scope.launch {
            try {
                val oldPid = _uiState.value.testOldPid ?: resolveServerPid()
                if (oldPid == null || oldPid <= 1 || oldPid == Process.myPid()) {
                    setTestPhase(NightDogTestPhase.ERROR, message = "pid_unresolved")
                    return@launch
                }
                val countBefore = getRecoveryCount()
                val startedAt = System.currentTimeMillis()

                setTestPhase(NightDogTestPhase.STOPPING, oldPid = oldPid)
                try {
                    Shizuku.exit()
                } catch (e: Throwable) {
                    LOGGER.w(e, "NightDog: Shizuku.exit() failed")
                }

                var dead = false
                repeat(20) {
                    if (!Shizuku.pingBinder()) {
                        dead = true
                        return@repeat
                    }
                    delay(250L)
                }
                if (!dead) {
                    setTestPhase(NightDogTestPhase.ERROR, oldPid = oldPid, message = "not_killed")
                    return@launch
                }
                setTestPhase(NightDogTestPhase.SERVER_DOWN, oldPid = oldPid)

                setTestPhase(NightDogTestPhase.WAITING_NIGHTDOG, oldPid = oldPid)
                val restored = withTimeoutOrNull(TEST_RECOVERY_TIMEOUT_MS) {
                    while (!Shizuku.pingBinder()) delay(500L)
                    true
                } ?: false

                if (!restored) {
                    setTestPhase(NightDogTestPhase.ERROR, oldPid = oldPid, message = "timeout")
                    return@launch
                }

                setTestPhase(NightDogTestPhase.VERIFYING, oldPid = oldPid)
                var newPid: Int? = null
                repeat(10) {
                    newPid = resolveServerPid()
                    if (newPid != null) return@repeat
                    delay(500L)
                }
                val resolvedNew = newPid
                if (resolvedNew == null || resolvedNew <= 1) {
                    setTestPhase(NightDogTestPhase.ERROR, oldPid = oldPid, message = "verify_failed")
                    return@launch
                }

                var countAfter = getRecoveryCount()
                repeat(8) {
                    if (countAfter >= countBefore + 1) return@repeat
                    delay(250L)
                    countAfter = getRecoveryCount()
                }
                val elapsed = System.currentTimeMillis() - startedAt

                _uiState.value = _uiState.value.copy(
                    testPhase = NightDogTestPhase.RECOVERED,
                    serverOnline = true,
                    serverPid = resolvedNew,
                    testOldPid = oldPid,
                    testNewPid = resolvedNew,
                    testElapsedMs = elapsed,
                    recoveryCount = countAfter,
                    lastRecoveryAt = getLastRecoveryAt(),
                    testMessage = null
                )
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                LOGGER.w(e, "NightDog: recovery test failed")
                setTestPhase(NightDogTestPhase.ERROR, message = "exception")
            } finally {
                testRunning.set(false)
            }
        }
    }

    fun dismissTestResult() {
        if (testRunning.get()) return
        _uiState.value = _uiState.value.copy(
            testPhase = NightDogTestPhase.IDLE,
            testOldPid = null,
            testNewPid = null,
            testElapsedMs = null,
            testMessage = null
        )
    }

    private fun setTestPhase(phase: NightDogTestPhase, oldPid: Int? = null, message: String? = null) {
        _uiState.value = _uiState.value.copy(
            testPhase = phase,
            testOldPid = oldPid ?: _uiState.value.testOldPid,
            testMessage = message
        )
    }

    // endregion
}
