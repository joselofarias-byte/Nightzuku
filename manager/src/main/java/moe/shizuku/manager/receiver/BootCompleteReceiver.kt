package moe.shizuku.manager.receiver

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.UserManager
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku

class BootCompleteReceiver : BroadcastReceiver() {

    override fun onReceive(context: Context, intent: Intent) {
        if (Intent.ACTION_LOCKED_BOOT_COMPLETED != intent.action
            && Intent.ACTION_BOOT_COMPLETED != intent.action) {
            return
        }

        // Safe Mode deliberately suppresses third-party recovery paths. Trying to
        // recreate a privileged service there works against Android's recovery
        // semantics and can make troubleshooting harder.
        if (context.packageManager.isSafeMode) {
            Log.w(AppConstants.TAG, "Skip start on boot while Android is in Safe Mode")
            return
        }

        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        NightDogRecovery.start(context)

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()
            || !NightDogRecovery.isDesiredRunning(context)) return

        if (ShizukuSettings.getLastLaunchMode() == LaunchMethod.ROOT) {
            rootStart(context)
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU
            && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            adbStart()
        } else {
            Log.w(AppConstants.TAG, "No support start on boot")
        }
    }

    private fun rootStart(context: Context) {
        if (!Shell.getShell().isRoot) {

            Shell.getCachedShell()?.close()
            return
        }

        Shell.cmd(Starter.internalCommand).exec()
    }

    @RequiresApi(Build.VERSION_CODES.TIRAMISU)
    private fun adbStart() {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                // Delega al motor single-flight de NightDog para evitar intentos ADB
                // simultáneos tras el arranque y respetar el estado de Android/usuario.
                NightDogRecovery.awaitActiveRecovery(BOOT_RECOVERY_WAIT_MS)
            } catch (error: Exception) {
                Log.w(AppConstants.TAG, "Falló la espera de recuperación ADB tras el arranque", error)
            } finally {
                pending.finish()
            }
        }
    }

    companion object {
        private const val BOOT_RECOVERY_WAIT_MS = 10_000L
    }
}
