package moe.shizuku.manager.shizuku

import android.content.Context
import android.os.Build
import android.os.SystemClock

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

    fun summary(context: Context): String {
        val p = prefs(context)
        val history = p.getString(KEY_HISTORY, "").orEmpty()
        if (history.isNotBlank()) return history
        val event = p.getString(KEY_EVENT, "sin registro") ?: "sin registro"
        val detail = p.getString(KEY_DETAIL, "") ?: ""
        val wall = p.getLong(KEY_WALL, 0L)
        val elapsed = p.getLong(KEY_ELAPSED, 0L)
        return "$wall|$elapsed|$event|$detail"
    }

    fun clear(context: Context) {
        prefs(context).edit().clear().commit()
    }
}
