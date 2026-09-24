package moe.shizuku.manager.dhizuku

import android.app.admin.DevicePolicyManager
import android.content.Context
import android.os.Build
import android.os.SystemProperties
import android.provider.Settings
import android.util.Log
import moe.shizuku.manager.utils.EnvironmentUtils

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

    override fun bindAdbTcp(port: Int): Boolean {
        val targetPort = port.takeIf { it in 1..65535 } ?: 5555
        return try {
            val cmd = "setprop service.adb.tcp.port $targetPort; setprop ctl.restart adbd || (stop adbd; start adbd)"
            val process = Runtime.getRuntime().exec(arrayOf("sh", "-c", cmd))
            val out = Thread { runCatching { process.inputStream.use { it.readBytes() } } }
            val err = Thread { runCatching { process.errorStream.use { it.readBytes() } } }
            out.start()
            err.start()
            val exit = process.waitFor()
            out.join(2_000)
            err.join(2_000)
            exit == 0 && waitForAdbTcpPort(targetPort)
        } catch (e: Throwable) {
            Log.e(TAG, "bindAdbTcp failed", e)
            false
        }
    }

    private fun waitForAdbTcpPort(port: Int): Boolean {
        repeat(10) {
            if (EnvironmentUtils.isAdbPortLive(port)) return true
            try {
                Thread.sleep(500)
            } catch (_: InterruptedException) {
                Thread.currentThread().interrupt()
                return false
            }
        }
        return false
    }

    companion object {
        private const val TAG = "NightzukuDhizuku"
    }
}
