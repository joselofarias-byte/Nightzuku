package moe.shizuku.manager.shizuku

import android.content.pm.PackageManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

class ShizukuStatusRepository {

    private val _status = MutableStateFlow<ShizukuRuntimeStatus>(ShizukuRuntimeStatus.Connecting)
    val status: StateFlow<ShizukuRuntimeStatus> = _status.asStateFlow()

    private var scope: CoroutineScope? = null
    private var pollingJob: Job? = null

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        scope?.launch(Dispatchers.IO) {
            _status.value = computeStatus()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        _status.value = ShizukuRuntimeStatus.NotRunning()
    }

    fun start(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        pollingJob?.cancel()
        pollingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = computeStatus()
                delay(nextDelay(_status.value))
            }
        }
    }

    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        pollingJob?.cancel()
        pollingJob = null
        scope = null
    }

    fun refresh() {
        scope?.launch(Dispatchers.IO) {
            _status.value = computeStatus()
        }
    }

    // ponytail: adaptive polling interval.
    // faster when server is down so the UI reacts quickly to startup.
    // slower when running to save battery.
    // replace with exponential backoff if reconnect storms appear.
    private fun nextDelay(status: ShizukuRuntimeStatus): Long = when (status) {
        is ShizukuRuntimeStatus.Running -> 12_000L
        is ShizukuRuntimeStatus.NotRunning -> 4_000L
        is ShizukuRuntimeStatus.PermissionRequired -> 8_000L
        is ShizukuRuntimeStatus.Error -> 4_000L
        is ShizukuRuntimeStatus.Connecting -> 4_000L
    }

    private fun computeStatus(): ShizukuRuntimeStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                return ShizukuRuntimeStatus.NotRunning()
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShizukuRuntimeStatus.PermissionRequired()
            }
            ShizukuRuntimeStatus.Running()
        } catch (e: Throwable) {
            ShizukuRuntimeStatus.Error(e)
        }
    }
}
