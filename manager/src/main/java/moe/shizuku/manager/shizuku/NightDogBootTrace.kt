package moe.shizuku.manager.shizuku

import android.content.Context
import android.os.Build
import android.os.SystemClock

/**
 * Tiny device-protected boot trace. It survives reboot and is readable before
 * unlock, so physical tests do not depend on logcat or a second monitor app.
 */
object NightDogBootTrace {

    private const val PREFS = "nightdog_boot_trace"
    private const val KEY_EVENT = "event"
    private const val KEY_DETAIL = "detail"
    private const val KEY_WALL = "wall"
    private const val KEY_ELAPSED = "elapsed"

    private fun prefs(context: Context) =
        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            context.applicationContext.createDeviceProtectedStorageContext()
        } else {
            context.applicationContext
        }).getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun note(context: Context, event: String, detail: String = "") {
        prefs(context).edit()
            .putString(KEY_EVENT, event)
            .putString(KEY_DETAIL, detail)
            .putLong(KEY_WALL, System.currentTimeMillis())
            .putLong(KEY_ELAPSED, SystemClock.elapsedRealtime())
            .apply()
    }

    fun summary(context: Context): String {
        val p = prefs(context)
        val event = p.getString(KEY_EVENT, "sin registro") ?: "sin registro"
        val detail = p.getString(KEY_DETAIL, "") ?: ""
        val wall = p.getLong(KEY_WALL, 0L)
        val elapsed = p.getLong(KEY_ELAPSED, 0L)
        return "event=$event; detail=$detail; wall=$wall; elapsed=$elapsed"
    }
}
