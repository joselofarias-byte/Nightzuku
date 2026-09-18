package moe.shizuku.manager.receiver

import android.Manifest.permission.WRITE_SECURE_SETTINGS
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
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
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.shizuku.NightDogRecovery
import moe.shizuku.manager.starter.Starter
import moe.shizuku.manager.utils.UserHandleCompat
import rikka.shizuku.Shizuku
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

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

        if (UserHandleCompat.myUserId() > 0 || Shizuku.pingBinder()) return

        if (!NightDogRecovery.isDesiredRunning(context)) {
            Log.w(AppConstants.TAG, "Skip start on boot; service was stopped manually")
            return
        }

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
        val cr = context.contentResolver
        Settings.Global.putInt(cr, "adb_wifi_enabled", 1)
        Settings.Global.putInt(cr, Settings.Global.ADB_ENABLED, 1)
        Settings.Global.putLong(cr, "adb_allowed_connection_time", 0L)
        val pending = goAsync()
        CoroutineScope(Dispatchers.IO).launch {
            val latch = CountDownLatch(1)
            val startLock = Any()
            val adbMdns = AdbMdns(context, AdbMdns.TLS_CONNECT) { port ->
                if (port <= 0) return@AdbMdns
                // NsdManager delivers this on the main thread. Hop to IO before any
                // blocking ADB connect / binder wait.
                launch {
                    synchronized(startLock) {
                        if (Shizuku.pingBinder()) {
                            latch.countDown()
                            return@launch
                        }
                        try {
                            val endpoint = AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)
                            val host = endpoint?.host ?: "127.0.0.1"
                            val resolvedPort = endpoint?.port ?: port
                            if (NightDogRecovery.startServerOverAdb(host, resolvedPort)) {
                                latch.countDown()
                            } else {
                                Log.w(AppConstants.TAG, "ADB start on boot did not bring the binder up at $host:$resolvedPort")
                            }
                        } catch (error: Exception) {
                            Log.w(AppConstants.TAG, "ADB start on boot failed", error)
                        }
                    }
                }
            }
            if (Settings.Global.getInt(cr, "adb_wifi_enabled", 0) == 1) {
                adbMdns.start()
                latch.await(BOOT_ADB_TIMEOUT_S, TimeUnit.SECONDS)
                adbMdns.stop()
            }
            pending.finish()
        }
    }

    companion object {
        // ponytail: Honor/MagicOS wireless debugging is often still advertising after 3s.
        // 12s stays inside a goAsync boot window without waiting forever.
        private const val BOOT_ADB_TIMEOUT_S = 12L
    }
}
