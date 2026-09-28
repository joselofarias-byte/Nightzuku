package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.shizuku.NightDogForegroundService
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (intent.action != Intent.ACTION_BOOT_COMPLETED &&
            intent.action != Intent.ACTION_MY_PACKAGE_REPLACED) return

        if (context.packageManager.isSafeMode) {
            Log.w(AppConstants.TAG, "Skip start on boot while Android is in Safe Mode")
            return
        }

        if (UserHandleCompat.myUserId() > 0 || !NightDogRecovery.isDesiredRunning(context)) return
        // The foreground service keeps NightDog alive. It performs recovery
        // without opening an Activity from the background.
        try {
            NightDogForegroundService.start(context)
            Log.i(AppConstants.TAG, "Boot recovery service requested")
        } catch (error: RuntimeException) {
            Log.w(AppConstants.TAG, "Boot recovery service could not start", error)
        }
    }
}
