package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.UserManager
import android.provider.Settings
import android.util.Log
import androidx.annotation.RequiresApi
import com.topjohnwu.superuser.Shell
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import moe.shizuku.manager.AppConstants
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.ShizukuSettings.LaunchMethod
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
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
            && context.checkSelfPermission(WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
            && ShizukuSettings.getLastLaunchMode() == LaunchMethod.ADB) {
            adbStart(context)
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
    private fun adbStart(context: Context) {
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            try {
                if (Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled", 0) != 1) {
                    Log.i(AppConstants.TAG, "ADB inalámbrico desactivado; se respeta el estado del usuario")
                    return@launch
                }
                val endpoint = AdbTransportResolver.discoverWireless(context)
                if (endpoint == null) {
                    Log.w(AppConstants.TAG, "Arranque: no se anunció un endpoint ADB local actual")
                    return@launch
                }
                val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
                AdbClient(endpoint.host, endpoint.port, key, requireTls = true).use { client ->
                    client.connect()
                    if (!NightDogRecovery.isDesiredRunning(context) || Shizuku.pingBinder()) return@use
                    client.shellCommand(Starter.internalCommand, null)
                }
            } catch (error: Exception) {
                Log.w(AppConstants.TAG, "Falló el inicio ADB tras el arranque", error)
            } finally {
                pending.finish()
            }
        }
    }
}
