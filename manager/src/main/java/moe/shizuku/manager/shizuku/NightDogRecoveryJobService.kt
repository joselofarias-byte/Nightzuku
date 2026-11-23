package moe.shizuku.manager.shizuku

import android.app.job.JobParameters
import android.app.job.JobService
import android.os.Build
import android.os.UserManager
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import rikka.shizuku.Shizuku

/**
 * JobService compatible con restricciones de segundo plano de Android 16 / MagicOS 10.
 * Reactiva el motor single-flight de NightDog tras reinicios, reconexiones Wi-Fi,
 * cambios en `adb_wifi_enabled` o terminación previa del proceso.
 */
class NightDogRecoveryJobService : JobService() {

    private val serviceScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var activeJob: Job? = null

    override fun onStartJob(params: JobParameters): Boolean {
        NightDogTrace.record(this, "JOB_START id=${params.jobId} ${NightDogTrace.state(this)}")
        val unlocked = runCatching {
            getSystemService(UserManager::class.java)?.isUserUnlocked ?: true
        }.getOrDefault(false)

        if (!unlocked) {
            return false
        }

        if (!NightDogRecovery.isDesiredRunning(applicationContext)) {
            NightDogJobScheduler.cancelAll(applicationContext)
            NightDogNotificationHelper.clear(applicationContext)
            return false
        }

        NightDogRecovery.start(applicationContext)

        if (runCatching { Shizuku.pingBinder() }.getOrDefault(false)) {
            return false
        }

        val jobId = params.jobId
        activeJob?.cancel()
        activeJob = serviceScope.launch {
            try {
                NightDogRecovery.onScheduledWakeup(applicationContext, jobId)
                NightDogRecovery.awaitActiveRecovery(JOB_WAIT_TIMEOUT_MS)
            } catch (error: Throwable) {
                Log.w(TAG, "Ejecución de NightDogRecoveryJobService interrumpida (jobId=$jobId)", error)
            } finally {
                // La programación de reintentos y triggers la gestiona NightDogRecoveryEngine.
                NightDogTrace.record(this@NightDogRecoveryJobService, "JOB_FINISH id=$jobId")
                jobFinished(params, false)
            }
        }
        return true
    }

    override fun onStopJob(params: JobParameters): Boolean {
        val reason = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) params.stopReason else null
        NightDogTrace.record(this, "JOB_STOP id=${params.jobId} reason=$reason")
        activeJob?.cancel()
        activeJob = null
        return false
    }

    override fun onDestroy() {
        super.onDestroy()
        serviceScope.cancel()
    }

    companion object {
        private const val TAG = "NightDogRecoveryJob"
        private const val JOB_WAIT_TIMEOUT_MS = 15_000L
    }
}
