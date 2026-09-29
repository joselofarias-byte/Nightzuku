package moe.shizuku.manager.shizuku

import android.app.job.JobInfo
import android.app.job.JobScheduler
import android.content.ComponentName
import android.content.Context
import android.os.Build
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.utils.UserHandleCompat

/**
 * Second boot path for OEMs that suppress/delay BOOT_COMPLETED.
 *
 * A persisted periodic JobScheduler job survives reboot and gives Nightzuku
 * another chance to load even if MagicOS never starts our boot receiver.
 */
object NightDogBootScheduler {

    private const val JOB_ID = 136001
    private const val PERIOD_MS = 15L * 60L * 1000L

    fun sync(context: Context) {
        val app = context.applicationContext
        if (UserHandleCompat.myUserId() > 0 || !NightDogRecovery.isDesiredRunning(app)) {
            cancel(app)
        } else {
            schedule(app)
        }
    }

    fun schedule(context: Context): Boolean {
        val app = context.applicationContext
        if (UserHandleCompat.myUserId() > 0 || !NightDogRecovery.isDesiredRunning(app)) {
            cancel(app)
            return false
        }

        val scheduler = app.getSystemService(JobScheduler::class.java) ?: return false
        val builder = JobInfo.Builder(
            JOB_ID,
            ComponentName(app, NightDogBootJobService::class.java)
        )
            .setPersisted(true)
            .setPeriodic(PERIOD_MS)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            builder.setRequiresBatteryNotLow(false)
        }

        val result = scheduler.schedule(builder.build())
        val ok = result == JobScheduler.RESULT_SUCCESS
        if (!ok) Log.w(AppConstants.TAG, "Could not schedule persisted NightDog rescue job")
        NightDogBootTrace.note(app, "job_schedule", if (ok) "ok" else "failed")
        return ok
    }

    fun cancel(context: Context) {
        val scheduler = context.applicationContext.getSystemService(JobScheduler::class.java)
        scheduler?.cancel(JOB_ID)
        NightDogBootTrace.note(context, "job_cancel", "desired running disabled")
    }
}
