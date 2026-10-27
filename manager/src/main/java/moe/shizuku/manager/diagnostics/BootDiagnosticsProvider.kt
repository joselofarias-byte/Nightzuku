package moe.shizuku.manager.diagnostics

import android.Manifest
import android.content.ContentProvider
import android.content.ContentValues
import android.content.pm.PackageManager
import android.database.Cursor
import android.database.MatrixCursor
import android.net.Uri
import android.os.Build
import android.os.SystemClock
import android.os.UserManager
import moe.shizuku.manager.persistence.SystemBootHardening
import moe.shizuku.manager.shizuku.NightDogBootTrace
import moe.shizuku.manager.shizuku.NightDogRecovery

/**
 * Read-only, signature-protected snapshot for the companion boot monitor.
 *
 * It runs in :bootdiag. ShizukuApplication deliberately skips NightDog startup
 * in that process so querying this provider does not change the result being
 * measured.
 */
class BootDiagnosticsProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun getType(uri: Uri): String =
        "vnd.android.cursor.item/vnd.com.joselofarias.nightzuku.bootdiag"

    override fun query(
        uri: Uri,
        projection: Array<out String>?,
        selection: String?,
        selectionArgs: Array<out String>?,
        sortOrder: String?
    ): Cursor? {
        val app = context?.applicationContext ?: return null
        if (uri.lastPathSegment != "snapshot") return null

        val trace = NightDogBootTrace.snapshot(app)
        val packageInfo = runCatching {
            app.packageManager.getPackageInfo(app.packageName, 0)
        }.getOrNull()
        val notificationsGranted =
            Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU ||
                app.checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) ==
                PackageManager.PERMISSION_GRANTED
        val userUnlocked = runCatching {
            app.getSystemService(UserManager::class.java)?.isUserUnlocked == true
        }.getOrDefault(false)

        val columns = arrayOf(
            "version_name",
            "desired_running",
            "hardening_applied",
            "notifications_granted",
            "user_unlocked",
            "boot_age_seconds",
            "receiver_seen",
            "receiver_skip_detail",
            "receiver_fgs_requested",
            "fgs_on_start",
            "fgs_foreground",
            "fgs_failure_detail",
            "job_start",
            "job_fgs_requested",
            "recovery_failure_detail",
            "binder_received_elapsed",
            "binder_lost_elapsed",
            "cleanup_stable_elapsed",
            "cleanup_skipped_elapsed",
            "recovery_failure_elapsed",
            "recover_now_elapsed",
            "manual_start_elapsed",
            "latest_event",
            "latest_detail",
            "trace"
        )
        return MatrixCursor(columns, 1).apply {
            addRow(arrayOf(
                packageInfo?.versionName.orEmpty(),
                if (NightDogRecovery.isDesiredRunning(app)) 1 else 0,
                if (SystemBootHardening.wasApplied(app)) 1 else 0,
                if (notificationsGranted) 1 else 0,
                if (userUnlocked) 1 else 0,
                SystemClock.elapsedRealtime() / 1_000L,
                if (trace.has("receiver")) 1 else 0,
                trace.receiverSkipDetail.orEmpty(),
                if (trace.has("receiver_fgs_requested")) 1 else 0,
                if (trace.has("fgs_on_start")) 1 else 0,
                if (trace.has("fgs_foreground")) 1 else 0,
                trace.foregroundFailureDetail.orEmpty(),
                if (trace.has("job_start")) 1 else 0,
                if (trace.has("job_fgs_requested")) 1 else 0,
                trace.recoveryFailureDetail.orEmpty(),
                trace.binderReceivedElapsed,
                trace.binderLostElapsed,
                trace.cleanupStableElapsed,
                trace.cleanupSkippedElapsed,
                trace.recoveryFailureElapsed,
                trace.recoverNowElapsed,
                trace.manualStartElapsed,
                trace.latestEvent.orEmpty(),
                trace.latestDetail.orEmpty(),
                trace.currentSummary
            ))
        }
    }

    override fun insert(uri: Uri, values: ContentValues?): Uri? =
        throw UnsupportedOperationException("read only")

    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int =
        throw UnsupportedOperationException("read only")

    override fun update(
        uri: Uri,
        values: ContentValues?,
        selection: String?,
        selectionArgs: Array<out String>?
    ): Int = throw UnsupportedOperationException("read only")
}
