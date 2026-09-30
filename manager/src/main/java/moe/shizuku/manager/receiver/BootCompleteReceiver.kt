package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.shizuku.NightDogBootScheduler
import moe.shizuku.manager.shizuku.NightDogBootTrace
import moe.shizuku.manager.shizuku.NightDogForegroundService
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.utils.UserHandleCompat

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.action ?: return
        if (action !in SUPPORTED_ACTIONS) return

        NightDogBootTrace.note(context, "receiver", action)

        if (context.packageManager.isSafeMode) {
            Log.w(AppConstants.TAG, "Skip start on boot while Android is in Safe Mode")
            NightDogBootTrace.note(context, "receiver_skip", "safe_mode:$action")
            return
        }

        if (UserHandleCompat.myUserId() > 0) {
            NightDogBootTrace.note(context, "receiver_skip", "secondary_user:$action")
            return
        }

        if (!NightDogRecovery.isDesiredRunning(context)) {
            NightDogBootScheduler.cancel(context)
            NightDogBootTrace.note(context, "receiver_skip", "desired_off:$action")
            return
        }

        // Persisted JobScheduler is the OEM fallback if MagicOS suppresses or
        // delays a later boot broadcast.
        NightDogBootScheduler.schedule(context)

        try {
            NightDogForegroundService.start(context)
            Log.i(AppConstants.TAG, "Boot recovery service requested by $action")
            NightDogBootTrace.note(context, "receiver_fgs_requested", action)
        } catch (error: RuntimeException) {
            Log.w(AppConstants.TAG, "Boot recovery service could not start from $action", error)
            NightDogBootTrace.note(
                context,
                "receiver_fgs_failed",
                "$action:${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    companion object {
        private const val QUICKBOOT_POWERON = "android.intent.action.QUICKBOOT_POWERON"

        private val SUPPORTED_ACTIONS = setOf(
            Intent.ACTION_LOCKED_BOOT_COMPLETED,
            Intent.ACTION_BOOT_COMPLETED,
            Intent.ACTION_USER_UNLOCKED,
            Intent.ACTION_MY_PACKAGE_REPLACED,
            QUICKBOOT_POWERON
        )
    }
}
