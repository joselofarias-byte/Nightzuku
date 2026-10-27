package moe.shizuku.manager.persistence

import android.content.Context
import android.content.Intent
import android.os.Build
import android.provider.Settings

/**
 * Opens the Android screens Nightzuku needs for no-root persistence.
 * Honor / MagicOS often omit the dedicated Wireless debugging activity.
 */
object AndroidDebugSettingsIntents {

    private val WIRELESS_DEBUGGING_ACTIONS = listOf(
        "android.settings.WIRELESS_DEBUGGING_SETTINGS",
        "com.android.settings.WIRELESS_DEBUGGING_SETTINGS"
    )

    fun openDeveloperOptions(context: Context): Boolean {
        return startFirstAvailable(
            context,
            listOf(
                Intent(Settings.ACTION_APPLICATION_DEVELOPMENT_SETTINGS),
                Intent(Settings.ACTION_DEVICE_INFO_SETTINGS)
            )
        )
    }

    fun openWirelessDebugging(context: Context): Boolean {
        val wirelessIntents = WIRELESS_DEBUGGING_ACTIONS.map { action ->
            Intent(action)
        }
        if (startFirstAvailable(context, wirelessIntents)) return true
        return openDeveloperOptions(context)
    }

    fun supportsWirelessDebuggingIntent(): Boolean {
        return Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    }

    private fun startFirstAvailable(context: Context, intents: List<Intent>): Boolean {
        for (intent in intents) {
            val launched = runCatching {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                if (intent.resolveActivity(context.packageManager) == null) {
                    false
                } else {
                    context.startActivity(intent)
                    true
                }
            }.getOrDefault(false)
            if (launched) return true
        }
        return false
    }
}
