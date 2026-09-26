package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemProperties
import android.os.UserManager
import android.provider.Settings
import android.util.Log

/**
 * Runs inside Dhizuku's Device Owner context.
 *
 * Keep the privileged surface deliberately small: Nightzuku only needs to
 * restore the ADB switches and (optionally) move adbd to a verified TCP port.
 */
class DhizukuService(private val context: Context) : IDhizukuService.Stub() {

    override fun enableAdb(): Boolean = setAdbEnabled(true)

    override fun setAdbEnabled(enabled: Boolean): Boolean {
        return try {
            val dpm = context.getSystemService(Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val ownerAdmin = dpm.activeAdmins
                ?.firstOrNull { admin ->
                    runCatching { dpm.isDeviceOwnerApp(admin.packageName) }.getOrDefault(false)
                }
                ?: run {
                    Log.e(TAG, "No Device Owner admin found")
                    return false
                }

            if (enabled) {
                // If the Device Owner itself previously applied the debugging
                // restriction, remove it before requesting ADB again.
                runCatching {
                    dpm.clearUserRestriction(ownerAdmin, UserManager.DISALLOW_DEBUGGING_FEATURES)
                }
            }

            dpm.setGlobalSetting(
                ownerAdmin,
                Settings.Global.ADB_ENABLED,
                if (enabled) "1" else "0"
            )

            // Android does not guarantee adb_wifi_enabled is accepted by DPM.
            // Try it only as a best-effort convenience; ADB_ENABLED remains the
            // authoritative operation and is verified by the client.
            if (enabled && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                runCatching {
                    dpm.setGlobalSetting(ownerAdmin, "adb_wifi_enabled", "1")
                }.onFailure {
                    Log.i(TAG, "Wireless debugging was not changed by DPM: ${it.message}")
                }
            }

            val actual = Settings.Global.getInt(
                context.contentResolver,
                Settings.Global.ADB_ENABLED,
                0
            ) == 1
            actual == enabled
        } catch (e: Throwable) {
            Log.e(TAG, "setAdbEnabled($enabled) failed", e)
            false
        }
    }

    override fun getAdbPort(): Int {
        return try {
            var port = SystemProperties.getInt("service.adb.tcp.port", -1)
            if (port <= 0) port = SystemProperties.getInt("persist.adb.tcp.port", -1)
            if (port <= 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                port = runCatching {
                    Settings.Global.getInt(context.contentResolver, "adb_wifi_port", -1)
                }.getOrDefault(-1)
            }
            port
        } catch (e: Throwable) {
            Log.e(TAG, "getAdbPort failed", e)
            -1
        }
    }

    companion object {
        private const val TAG = "NightzukuDhizuku"
    }
}
