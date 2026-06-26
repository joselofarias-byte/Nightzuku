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
        _status.value = ShizukuRuntimeStatus.NotRunning
    }

    fun start(coroutineScope: CoroutineScope) {
        scope = coroutineScope
        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)
        pollingJob?.cancel()
        pollingJob = coroutineScope.launch(Dispatchers.IO) {
            while (isActive) {
                _status.value = computeStatus()
                delay(12_000L)
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

    private fun computeStatus(): ShizukuRuntimeStatus {
        return try {
            if (!Shizuku.pingBinder()) {
                return ShizukuRuntimeStatus.NotRunning
            }
            if (Shizuku.checkSelfPermission() != PackageManager.PERMISSION_GRANTED) {
                return ShizukuRuntimeStatus.PermissionRequired
            }
            ShizukuRuntimeStatus.Running
        } catch (e: Throwable) {
            ShizukuRuntimeStatus.Error(e)
        }
    }
}
