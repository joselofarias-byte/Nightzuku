package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_BOOT_COMPLETED != intent.action) return

        if (context.packageManager.isSafeMode) {
            Log.w(AppConstants.TAG, "Skip start on boot while Android is in Safe Mode")
            return
        }

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        val pending = goAsync()
        CoroutineScope(Dispatchers.Default).launch {
            try {
                // Application.onCreate starts NightDog before this receiver runs.
                // Give Android a brief post-unlock settle window, then request the
                // same recovery path already validated manually on the HONOR 200.
                delay(1_500L)
                NightDogRecovery.requestImmediateRecovery(context.applicationContext)
                Log.i(AppConstants.TAG, "Boot recovery delegated to NightDog")
            } catch (error: Throwable) {
                Log.w(AppConstants.TAG, "Boot recovery request failed", error)
            } finally {
                pending.finish()
            }
        }
    }
}
