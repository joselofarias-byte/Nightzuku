package moe.shizuku.manager.shizuku

import android.content.Context
import android.os.Build
import android.os.SystemClock
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Bounded device-protected boot trace. It survives reboot and is readable
 * before unlock, so physical tests do not depend on logcat or a second app.
 */
object NightDogBootTrace {

    private const val PREFS = "nightdog_boot_trace"
    private const val KEY_EVENT = "event"
    private const val KEY_DETAIL = "detail"
    private const val KEY_WALL = "wall"
    private const val KEY_ELAPSED = "elapsed"
    private const val KEY_HISTORY = "history"
    private const val MAX_HISTORY = 20
    private const val BOOT_CLOCK_TOLERANCE_MS = 2_000L

    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    @Synchronized
    fun note(context: Context, event: String, detail: String = "") {
        val p = prefs(context)
        val wall = System.currentTimeMillis()
        val elapsed = SystemClock.elapsedRealtime()
        val cleanDetail = detail.replace('\n', ' ').replace('\r', ' ')
        val line = "$wall|$elapsed|$event|$cleanDetail"
        val previous = p.getString(KEY_HISTORY, "")
            .orEmpty()
            .lineSequence()
            .filter { it.isNotBlank() }
            .toMutableList()
        previous.add(line)
        val bounded = previous.takeLast(MAX_HISTORY).joinToString("\n")

        p.edit()
            .putString(KEY_EVENT, event)
            .putString(KEY_DETAIL, cleanDetail)
            .putLong(KEY_WALL, wall)
            .putLong(KEY_ELAPSED, elapsed)
            .putString(KEY_HISTORY, bounded)
            .commit()
    }

    data class BootSnapshot(
        val currentSummary: String,
        val events: Set<String>,
        val latestEvent: String?,
        val latestDetail: String?,
        val receiverSkipDetail: String?,
        val foregroundFailureDetail: String?,
        val recoveryFailureDetail: String?,
        val binderReceivedElapsed: Long,
        val binderLostElapsed: Long,
        val cleanupStableElapsed: Long,
        val cleanupSkippedElapsed: Long,
        val recoveryFailureElapsed: Long,
        val recoverNowElapsed: Long,
        val manualStartElapsed: Long
    ) {
        fun has(event: String): Boolean = event in events
    }

    private data class Entry(
        val wall: Long,
        val elapsed: Long,
        val event: String,
        val detail: String
    )

    fun summary(context: Context, maxLines: Int = 8): String {
        val p = prefs(context)
        val history = p.getString(KEY_HISTORY, "").orEmpty()
        if (history.isNotBlank()) {
            return history.lineSequence()
                .filter { it.isNotBlank() }
                .toList()
                .takeLast(maxLines.coerceAtLeast(1))
                .joinToString("\n") { formatLine(it) }
        }
        val event = p.getString(KEY_EVENT, "sin registro") ?: "sin registro"
        val detail = p.getString(KEY_DETAIL, "") ?: ""
        val wall = p.getLong(KEY_WALL, 0L)
        val elapsed = p.getLong(KEY_ELAPSED, 0L)
        return "$wall|$elapsed|$event|$detail"
    }

    fun snapshot(context: Context, maxLines: Int = MAX_HISTORY): BootSnapshot {
        val history = prefs(context).getString(KEY_HISTORY, "").orEmpty()
        val bootEpoch = System.currentTimeMillis() - SystemClock.elapsedRealtime()
        val current = history.lineSequence()
            .mapNotNull(::parseLine)
            .filter { it.wall >= bootEpoch - BOOT_CLOCK_TOLERANCE_MS }
            .toList()
            .takeLast(maxLines.coerceAtLeast(1))

        val latest = current.lastOrNull()
        return BootSnapshot(
            currentSummary = current.joinToString("\n") { formatEntry(it) },
            events = current.mapTo(linkedSetOf()) { it.event },
            latestEvent = latest?.event,
            latestDetail = latest?.detail,
            receiverSkipDetail = current.lastOrNull { it.event == "receiver_skip" }?.detail,
            foregroundFailureDetail = current.lastOrNull { it.event == "fgs_failed" }?.detail,
            recoveryFailureDetail = current.lastOrNull { it.event == "recovery_failure" }?.detail,
            binderReceivedElapsed = current.lastOrNull { it.event == "binder_received" }?.elapsed ?: 0L,
            binderLostElapsed = current.lastOrNull { it.event == "binder_lost" }?.elapsed ?: 0L,
            cleanupStableElapsed = current.lastOrNull { it.event == "transport_cleanup_stable" }?.elapsed ?: 0L,
            cleanupSkippedElapsed = current.lastOrNull { it.event == "transport_cleanup_skipped" }?.elapsed ?: 0L,
            recoveryFailureElapsed = current.lastOrNull { it.event == "recovery_failure" }?.elapsed ?: 0L,
            recoverNowElapsed = current.lastOrNull { it.event == "recover_now" }?.elapsed ?: 0L,
            manualStartElapsed = current.lastOrNull { it.event == "manual_start" }?.elapsed ?: 0L
        )
    }

    private fun parseLine(raw: String): Entry? {
        val parts = raw.split('|', limit = 4)
        if (parts.size < 4) return null
        return Entry(
            wall = parts[0].toLongOrNull() ?: return null,
            elapsed = parts[1].toLongOrNull() ?: return null,
            event = parts[2],
            detail = parts[3]
        )
    }

    private fun formatLine(raw: String): String =
        parseLine(raw)?.let(::formatEntry) ?: raw

    private fun formatEntry(entry: Entry): String {
        val time = runCatching {
            SimpleDateFormat("HH:mm:ss", Locale.getDefault()).format(Date(entry.wall))
        }.getOrDefault(entry.wall.toString())
        return buildString {
            append(time)
            append(" · ")
            append(entry.event)
            if (entry.detail.isNotBlank()) {
                append(" · ")
                append(entry.detail)
            }
        }
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
