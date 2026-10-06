package com.joselofarias.nightzuku.monitor;

import android.content.Context;
import android.database.Cursor;
import android.net.Uri;

final class NightzukuDiagnostics {
    private static final Uri SNAPSHOT =
        Uri.parse("content://com.joselofarias.nightzuku.bootdiag/snapshot");

    static final class Snapshot {
        final boolean available;
        final String error;
        final String versionName;
        final boolean desiredRunning;
        final boolean hardeningApplied;
        final boolean notificationsGranted;
        final boolean userUnlocked;
        final long bootAgeSeconds;
        final boolean receiverSeen;
        final String receiverSkipDetail;
        final boolean receiverFgsRequested;
        final boolean fgsOnStart;
        final boolean fgsForeground;
        final String fgsFailureDetail;
        final boolean jobStart;
        final boolean jobFgsRequested;
        final String recoveryFailureDetail;
        final long binderReceivedElapsed;
        final long binderLostElapsed;
        final long cleanupStableElapsed;
        final long cleanupSkippedElapsed;
        final long recoveryFailureElapsed;
        final long recoverNowElapsed;
        final long manualStartElapsed;
        final String latestEvent;
        final String latestDetail;
        final String trace;

        Snapshot(
            boolean available,
            String error,
            String versionName,
            boolean desiredRunning,
            boolean hardeningApplied,
            boolean notificationsGranted,
            boolean userUnlocked,
            long bootAgeSeconds,
            boolean receiverSeen,
            String receiverSkipDetail,
            boolean receiverFgsRequested,
            boolean fgsOnStart,
            boolean fgsForeground,
            String fgsFailureDetail,
            boolean jobStart,
            boolean jobFgsRequested,
            String recoveryFailureDetail,
            long binderReceivedElapsed,
            long binderLostElapsed,
            long cleanupStableElapsed,
            long cleanupSkippedElapsed,
            long recoveryFailureElapsed,
            long recoverNowElapsed,
            long manualStartElapsed,
            String latestEvent,
            String latestDetail,
            String trace
        ) {
            this.available = available;
            this.error = error;
            this.versionName = versionName;
            this.desiredRunning = desiredRunning;
            this.hardeningApplied = hardeningApplied;
            this.notificationsGranted = notificationsGranted;
            this.userUnlocked = userUnlocked;
            this.bootAgeSeconds = bootAgeSeconds;
            this.receiverSeen = receiverSeen;
            this.receiverSkipDetail = receiverSkipDetail;
            this.receiverFgsRequested = receiverFgsRequested;
            this.fgsOnStart = fgsOnStart;
            this.fgsForeground = fgsForeground;
            this.fgsFailureDetail = fgsFailureDetail;
            this.jobStart = jobStart;
            this.jobFgsRequested = jobFgsRequested;
            this.recoveryFailureDetail = recoveryFailureDetail;
            this.binderReceivedElapsed = binderReceivedElapsed;
            this.binderLostElapsed = binderLostElapsed;
            this.cleanupStableElapsed = cleanupStableElapsed;
            this.cleanupSkippedElapsed = cleanupSkippedElapsed;
            this.recoveryFailureElapsed = recoveryFailureElapsed;
            this.recoverNowElapsed = recoverNowElapsed;
            this.manualStartElapsed = manualStartElapsed;
            this.latestEvent = latestEvent;
            this.latestDetail = latestDetail;
            this.trace = trace;
        }

        static Snapshot unavailable(String error) {
            return new Snapshot(
                false, error, "", true, false, true, true, 0L,
                false, "", false, false, false, "", false, false,
                "", 0L, 0L, 0L, 0L, 0L, 0L, 0L, "", "", ""
            );
        }
    }

    private NightzukuDiagnostics() {}

    static Snapshot read(Context context) {
        try (Cursor c = context.getContentResolver().query(
            SNAPSHOT, null, null, null, null
        )) {
            if (c == null || !c.moveToFirst()) {
                return Snapshot.unavailable("Proveedor sin respuesta");
            }
            return new Snapshot(
                true,
                "",
                string(c, "version_name"),
                bool(c, "desired_running"),
                bool(c, "hardening_applied"),
                bool(c, "notifications_granted"),
                bool(c, "user_unlocked"),
                number(c, "boot_age_seconds"),
                bool(c, "receiver_seen"),
                string(c, "receiver_skip_detail"),
                bool(c, "receiver_fgs_requested"),
                bool(c, "fgs_on_start"),
                bool(c, "fgs_foreground"),
                string(c, "fgs_failure_detail"),
                bool(c, "job_start"),
                bool(c, "job_fgs_requested"),
                string(c, "recovery_failure_detail"),
                number(c, "binder_received_elapsed"),
                number(c, "binder_lost_elapsed"),
                number(c, "cleanup_stable_elapsed"),
                number(c, "cleanup_skipped_elapsed"),
                number(c, "recovery_failure_elapsed"),
                number(c, "recover_now_elapsed"),
                number(c, "manual_start_elapsed"),
                string(c, "latest_event"),
                string(c, "latest_detail"),
                string(c, "trace")
            );
        } catch (Throwable error) {
            return Snapshot.unavailable(
                error.getClass().getSimpleName() + ": " +
                    (error.getMessage() == null ? "" : error.getMessage())
            );
        }
    }

    private static boolean bool(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i >= 0 && c.getInt(i) != 0;
    }

    private static long number(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        return i >= 0 ? c.getLong(i) : 0L;
    }

    private static String string(Cursor c, String column) {
        int i = c.getColumnIndex(column);
        if (i < 0 || c.isNull(i)) return "";
        String value = c.getString(i);
        return value == null ? "" : value;
    }
}
