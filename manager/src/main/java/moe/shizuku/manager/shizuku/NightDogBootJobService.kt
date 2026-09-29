package moe.shizuku.manager.shizuku

import android.app.job.JobParameters
import android.app.job.JobService
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

/**
 * Persisted OEM-fallback. The normal path remains BOOT_COMPLETED; this job is
 * intentionally a low-frequency rescue path when an OEM suppresses it.
 */
class NightDogBootJobService : JobService() {

    override fun onStartJob(params: JobParameters?): Boolean {
        NightDogBootTrace.note(
            this,
            "job_start",
            "binder=${runCatching { Shizuku.pingBinder() }.getOrDefault(false)}"
        )

        if (packageManager.isSafeMode ||
            UserHandleCompat.myUserId() > 0 ||
            !NightDogRecovery.isDesiredRunning(this)
        ) {
            if (!NightDogRecovery.isDesiredRunning(this)) NightDogBootScheduler.cancel(this)
            return false
        }

        return try {
            NightDogForegroundService.start(this)
            NightDogBootTrace.note(this, "job_fgs_requested", "ok")
            false
        } catch (error: RuntimeException) {
            Log.w(AppConstants.TAG, "Persisted NightDog rescue job could not start foreground service", error)
            NightDogBootTrace.note(
                this,
                "job_fgs_requested",
                "failed:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
            // Ask JobScheduler for another opportunity; system backoff applies.
            true
        }
    }

    override fun onStopJob(params: JobParameters?): Boolean {
        NightDogBootTrace.note(this, "job_stopped", "reschedule")
        return NightDogRecovery.isDesiredRunning(this)
    }
}
