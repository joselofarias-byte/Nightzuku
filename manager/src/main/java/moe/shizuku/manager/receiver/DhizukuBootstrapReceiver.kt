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

/**
 * Explicit boot relay endpoint for the installed Dhizuku Device Owner.
 *
 * MagicOS may suppress Nightzuku's own boot receiver even when OEM autostart
 * toggles are enabled. Dhizuku is already a Device Owner and receives boot
 * events independently; it can explicitly wake this receiver.
 */
class DhizukuBootstrapReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != ACTION_BOOTSTRAP) return

        val trigger = intent.getStringExtra(EXTRA_TRIGGER).orEmpty().ifBlank { "dhizuku" }
        NightDogBootTrace.note(context, "dhizuku_bootstrap", trigger)

        if (context.packageManager.isSafeMode) {
            NightDogBootTrace.note(context, "dhizuku_bootstrap_skip", "safe_mode")
            return
        }

        if (UserHandleCompat.myUserId() > 0) {
            NightDogBootTrace.note(context, "dhizuku_bootstrap_skip", "secondary_user")
            return
        }

        if (!NightDogRecovery.isDesiredRunning(context)) {
            NightDogBootTrace.note(context, "dhizuku_bootstrap_skip", "desired_off")
            return
        }

        NightDogBootScheduler.schedule(context)

        try {
            NightDogForegroundService.start(context)
            NightDogBootTrace.note(context, "dhizuku_bootstrap_fgs", "requested")
        } catch (error: RuntimeException) {
            Log.w(AppConstants.TAG, "Dhizuku boot relay could not start NightDog FGS", error)
            NightDogBootTrace.note(
                context,
                "dhizuku_bootstrap_fgs_failed",
                "${error.javaClass.simpleName}:${error.message.orEmpty()}"
            )
        }
    }

    companion object {
        const val ACTION_BOOTSTRAP =
            "com.joselofarias.nightzuku.action.DHIZUKU_BOOTSTRAP"
        const val EXTRA_TRIGGER = "trigger"
    }
}
