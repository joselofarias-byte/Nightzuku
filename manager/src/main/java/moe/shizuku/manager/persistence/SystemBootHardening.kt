package moe.shizuku.manager.persistence

import android.app.ActivityManager
import android.app.usage.UsageStatsManager
import android.content.Context
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbLocalWirelessDiscovery
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.dhizuku.DhizukuDeviceOwnerBridge
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets

/**
 * Prepares persistent Android-side exemptions before reboot.
 *
 * This deliberately does not use a VPN, accessibility service, notification
 * listener or a modified Dhizuku APK. It uses the already authenticated local
 * ADB transport plus the already installed Dhizuku Device Owner.
 */
object SystemBootHardening {

    data class Report(
        val success: Boolean,
        val endpoint: String?,
        val deviceOwnerProtected: Boolean?,
        val deviceIdleWhitelisted: Boolean,
        val standbyActive: Boolean,
        val backgroundRestricted: Boolean,
        val output: String,
        val message: String
    )

    suspend fun apply(context: Context): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val packageName = app.packageName

        val ownerProtected = DhizukuDeviceOwnerBridge
            .setNightzukuUserControlProtected(app, true)
            .getOrNull()

        val command = buildString {
            append("PKG=")
            append(shellQuote(packageName))
            append("; ")
            append("cmd deviceidle whitelist +\$PKG 2>&1 || true; ")
            append("am set-inactive \$PKG false 2>&1 || true; ")
            append("am set-standby-bucket \$PKG active 2>&1 || true; ")
            append("cmd appops set \$PKG RUN_IN_BACKGROUND allow 2>&1 || true; ")
            append("cmd appops set \$PKG RUN_ANY_IN_BACKGROUND allow 2>&1 || true; ")
            append("cmd appops set \$PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED ignore 2>&1 || true; ")
            append("cmd app_hibernation set-state --user 0 \$PKG false 2>&1 || true; ")
            append("echo __NZ_IDLE__; ")
            append("dumpsys deviceidle whitelist 2>/dev/null | grep -F \$PKG || true; ")
            append("echo __NZ_BUCKET__; ")
            append("am get-standby-bucket \$PKG 2>&1 || true; ")
            append("echo __NZ_BG1__; ")
            append("cmd appops get \$PKG RUN_IN_BACKGROUND 2>&1 || true; ")
            append("echo __NZ_BG2__; ")
            append("cmd appops get \$PKG RUN_ANY_IN_BACKGROUND 2>&1 || true")
        }

        val execution = executeOverAuthenticatedAdb(command)
        val output = execution.second
        val endpoint = execution.first

        val idleWhitelisted = output
            .substringAfter("__NZ_IDLE__", "")
            .substringBefore("__NZ_BUCKET__", "")
            .contains(packageName)

        val bucket = output
            .substringAfter("__NZ_BUCKET__", "")
            .substringBefore("__NZ_BG1__", "")
            .trim()
        val standbyActive = bucket == "10" ||
            bucket.equals("active", ignoreCase = true) ||
            bucket.lineSequence().any { it.trim() == "10" }

        val backgroundRestricted = runCatching {
            app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        }.getOrDefault(false)

        val essentialsOk = endpoint != null && idleWhitelisted && standbyActive
        val ownerOk = ownerProtected != false

        val report = Report(
            success = essentialsOk && ownerOk && !backgroundRestricted,
            endpoint = endpoint,
            deviceOwnerProtected = ownerProtected,
            deviceIdleWhitelisted = idleWhitelisted,
            standbyActive = standbyActive,
            backgroundRestricted = backgroundRestricted,
            output = output.takeLast(MAX_REPORT_CHARS),
            message = buildString {
                append(if (essentialsOk) "Exenciones Android aplicadas." else "Exenciones Android incompletas.")
                if (ownerProtected == true) append(" Device Owner protege Nightzuku.")
                if (ownerProtected == false) append(" Device Owner no confirmó la protección.")
                if (backgroundRestricted) append(" Android aún marca ejecución en segundo plano como restringida.")
            }
        )
        markApplied(app, report.success)
        report
    }

    suspend fun remove(context: Context): Report = withContext(Dispatchers.IO) {
        val app = context.applicationContext
        val packageName = app.packageName

        val ownerProtected = DhizukuDeviceOwnerBridge
            .setNightzukuUserControlProtected(app, false)
            .getOrNull()

        val command = buildString {
            append("PKG=")
            append(shellQuote(packageName))
            append("; ")
            append("cmd deviceidle whitelist -\$PKG 2>&1 || true; ")
            append("cmd appops set \$PKG RUN_IN_BACKGROUND default 2>&1 || true; ")
            append("cmd appops set \$PKG RUN_ANY_IN_BACKGROUND default 2>&1 || true; ")
            append("cmd appops set \$PKG AUTO_REVOKE_PERMISSIONS_IF_UNUSED default 2>&1 || true; ")
            append("echo __NZ_IDLE__; ")
            append("dumpsys deviceidle whitelist 2>/dev/null | grep -F \$PKG || true; ")
            append("echo __NZ_BUCKET__; ")
            append("am get-standby-bucket \$PKG 2>&1 || true")
        }

        val execution = executeOverAuthenticatedAdb(command)
        val output = execution.second
        val endpoint = execution.first

        val idleWhitelisted = output
            .substringAfter("__NZ_IDLE__", "")
            .substringBefore("__NZ_BUCKET__", "")
            .contains(packageName)

        val bucket = output
            .substringAfter("__NZ_BUCKET__", "")
            .trim()
        val standbyActive = bucket == "10" ||
            bucket.equals("active", ignoreCase = true) ||
            bucket.lineSequence().any { it.trim() == "10" }

        val backgroundRestricted = runCatching {
            app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        }.getOrDefault(false)

        val report = Report(
            success = endpoint != null && !idleWhitelisted && ownerProtected != true,
            endpoint = endpoint,
            deviceOwnerProtected = ownerProtected,
            deviceIdleWhitelisted = idleWhitelisted,
            standbyActive = standbyActive,
            backgroundRestricted = backgroundRestricted,
            output = output.takeLast(MAX_REPORT_CHARS),
            message = "Blindaje persistente retirado; app-ops devueltos a default."
        )
        markApplied(app, false)
        report
    }

    fun wasApplied(context: Context): Boolean {
        val app = context.applicationContext
        val prefsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
        return prefsContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getBoolean(KEY_APPLIED, false)
    }

    fun localStatus(context: Context): Pair<Int?, Boolean> {
        val app = context.applicationContext
        val bucket = runCatching {
            app.getSystemService(UsageStatsManager::class.java)?.appStandbyBucket
        }.getOrNull()
        val restricted = runCatching {
            app.getSystemService(ActivityManager::class.java)?.isBackgroundRestricted == true
        }.getOrDefault(false)
        return bucket to restricted
    }

    private fun executeOverAuthenticatedAdb(command: String): Pair<String?, String> {
        val sources = linkedSetOf<Pair<String, Int>>()

        AdbTransportResolver.persistentTcpEndpoint()?.let { sources.add(it.host to it.port) }
        AdbTransportResolver.systemAdbTcpEndpoint()?.let { sources.add(it.host to it.port) }
        AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)?.let { sources.add(it.host to it.port) }
        AdbLocalWirelessDiscovery.lastKnownEndpoint()?.let { sources.add(it.host to it.port) }
        AdbLocalWirelessDiscovery.discover()?.let { sources.add(it.host to it.port) }

        if (sources.isEmpty()) return null to "No authenticated ADB endpoint is available."

        val key = AdbKey(
            PreferenceAdbKeyStore(ShizukuSettings.getPreferences()),
            "shizuku"
        )

        var lastError = ""
        for ((host, port) in sources) {
            val output = ByteArrayOutputStream()
            val attempt = runCatching {
                AdbClient(host, port, key).use { client ->
                    client.connect()
                    client.shellCommand(command) { bytes ->
                        output.write(bytes)
                    }
                }
            }
            if (attempt.isSuccess) {
                return "$host:$port" to output.toString(StandardCharsets.UTF_8.name())
            }
            lastError = attempt.exceptionOrNull()?.let {
                "${it.javaClass.simpleName}: ${it.message.orEmpty()}"
            }.orEmpty()
        }

        return null to lastError
    }

    private fun markApplied(context: Context, applied: Boolean) {
        val app = context.applicationContext
        val prefsContext = if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.N) {
            app.createDeviceProtectedStorageContext()
        } else {
            app
        }
        prefsContext
            .getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putBoolean(KEY_APPLIED, applied)
            .apply()
    }

    private fun shellQuote(value: String): String {
        return "'" + value.replace("'", "'\\''") + "'"
    }

    private const val PREFS_NAME = "system_boot_hardening"
    private const val KEY_APPLIED = "applied"
    private const val MAX_REPORT_CHARS = 8_000
}
