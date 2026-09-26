package moe.shizuku.manager.persistence

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.provider.Settings
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbEndpoint
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbLocalWirelessDiscovery
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.PreferenceAdbKeyStore

/**
 * Lets Nightzuku temporarily disable Developer options and later restore them
 * without requiring a second app, root, Termux, or a fresh ADB session.
 *
 * The one-time prerequisite is WRITE_SECURE_SETTINGS. Nightzuku can grant it
 * to itself while an authenticated ADB transport is still available. The grant
 * belongs to the package and survives turning Developer options / ADB off.
 */
object DeveloperOptionsController {

    data class Snapshot(
        val writeSecureSettingsGranted: Boolean,
        val developerOptionsEnabled: Boolean,
        val adbEnabled: Boolean,
        val wirelessDebuggingEnabled: Boolean,
        val restorePending: Boolean
    )

    data class Result(
        val success: Boolean,
        val snapshot: Snapshot,
        val detail: String? = null
    )

    fun shouldRestoreForRecovery(snapshot: Snapshot): Boolean {
        return snapshot.writeSecureSettingsGranted &&
            snapshot.restorePending &&
            (!snapshot.developerOptionsEnabled ||
                !snapshot.adbEnabled ||
                !snapshot.wirelessDebuggingEnabled)
    }

    fun shouldEnableForManualRecovery(snapshot: Snapshot): Boolean {
        return snapshot.writeSecureSettingsGranted &&
            (!snapshot.developerOptionsEnabled ||
                !snapshot.adbEnabled ||
                !snapshot.wirelessDebuggingEnabled)
    }

    fun snapshot(context: Context): Snapshot {
        val resolver = context.contentResolver
        val preferences = ShizukuSettings.getPreferences()
        return Snapshot(
            writeSecureSettingsGranted = hasWriteSecureSettings(context),
            developerOptionsEnabled = readGlobalBoolean(resolver, DEVELOPMENT_SETTINGS_ENABLED),
            adbEnabled = readGlobalBoolean(resolver, ADB_ENABLED),
            wirelessDebuggingEnabled = readGlobalBoolean(resolver, ADB_WIFI_ENABLED),
            restorePending = preferences.getBoolean(PREF_RESTORE_PENDING, false)
        )
    }

    suspend fun prepare(context: Context): Result = withContext(Dispatchers.IO) {
        if (hasWriteSecureSettings(context)) {
            return@withContext Result(true, snapshot(context), "already_granted")
        }

        val endpoints = linkedSetOf<AdbEndpoint>()
        AdbTransportResolver.persistentTcpEndpoint()?.let(endpoints::add)
        AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)?.let(endpoints::add)
        AdbLocalWirelessDiscovery.lastKnownEndpoint()?.let(endpoints::add)
        AdbTransportResolver.systemAdbTcpEndpoint()?.let(endpoints::add)

        var lastError: String? = null

        suspend fun tryGrant(endpoint: AdbEndpoint): Boolean {
            return runCatching {
                val key = AdbKey(
                    PreferenceAdbKeyStore(ShizukuSettings.getPreferences()),
                    "shizuku"
                )
                AdbClient(endpoint.host, endpoint.port, key).use { client ->
                    client.connect()
                    client.shellCommand(
                        "/system/bin/pm grant ${context.packageName} ${Manifest.permission.WRITE_SECURE_SETTINGS}",
                        null
                    )
                }

                repeat(10) {
                    if (hasWriteSecureSettings(context)) return@runCatching true
                    delay(100L)
                }
                hasWriteSecureSettings(context)
            }.onFailure { error ->
                lastError = "${endpoint.host}:${endpoint.port}: ${error.message ?: error.javaClass.simpleName}"
            }.getOrDefault(false)
        }

        for (endpoint in endpoints) {
            if (tryGrant(endpoint)) {
                return@withContext Result(true, snapshot(context), "granted")
            }
        }

        // A saved TCP endpoint can be stale after Android rotates or restarts
        // adbd. Fall back to the loopback protocol scan even when other
        // candidates existed, rather than only when the initial set was empty.
        val dynamicEndpoint = AdbLocalWirelessDiscovery.discover()
        if (dynamicEndpoint != null && dynamicEndpoint !in endpoints) {
            if (tryGrant(dynamicEndpoint)) {
                return@withContext Result(true, snapshot(context), "granted_dynamic")
            }
        }

        Result(
            false,
            snapshot(context),
            lastError ?: "No authenticated ADB endpoint is currently available"
        )
    }

    suspend fun disableTemporarily(context: Context): Result = withContext(Dispatchers.IO) {
        val prepared = if (hasWriteSecureSettings(context)) {
            Result(true, snapshot(context), "already_granted")
        } else {
            prepare(context)
        }
        if (!prepared.success) return@withContext prepared

        val before = snapshot(context)
        val preferences = ShizukuSettings.getPreferences()

        // Only capture the original state once. Repeated OFF taps must not
        // overwrite the real restore target with an already-disabled state.
        if (!before.restorePending) {
            preferences.edit()
                .putBoolean(PREF_PREVIOUS_DEVELOPER, before.developerOptionsEnabled)
                .putBoolean(PREF_PREVIOUS_ADB, before.adbEnabled)
                .putBoolean(PREF_PREVIOUS_WIRELESS_ADB, before.wirelessDebuggingEnabled)
                .putBoolean(PREF_RESTORE_PENDING, true)
                .apply()
        }

        val resolver = context.contentResolver
        return@withContext runCatching {
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 0)
            Settings.Global.putInt(resolver, ADB_ENABLED, 0)
            Settings.Global.putInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 0)

            val after = snapshot(context)
            val success = !after.developerOptionsEnabled &&
                !after.adbEnabled &&
                !after.wirelessDebuggingEnabled
            Result(
                success,
                after,
                if (success) "disabled" else "Settings.Global did not retain the requested OFF state"
            )
        }.getOrElse { error ->
            Result(false, snapshot(context), error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Re-enables the ADB transport without touching the visible Developer options switch.
     *
     * This mirrors the recovery path proven by Stellar: WRITE_SECURE_SETTINGS survives
     * disabling Developer options, so Nightzuku can bring adbd back first and reuse a
     * previously authenticated local TCP endpoint. Wireless debugging is only requested
     * as a second-stage fallback.
     */
    suspend fun enableAdbTransportForRecovery(
        context: Context,
        enableWireless: Boolean = false
    ): Result = withContext(Dispatchers.IO) {
        if (!hasWriteSecureSettings(context)) {
            return@withContext Result(
                false,
                snapshot(context),
                "WRITE_SECURE_SETTINGS is not granted"
            )
        }

        val before = snapshot(context)
        val preferences = ShizukuSettings.getPreferences()
        val transportPending = preferences.getBoolean(PREF_TRANSPORT_RESTORE_PENDING, false)
        if (TransportRestorePolicy.shouldCaptureTransportSnapshot(transportPending)) {
            val captured = TransportRestorePolicy.captureTransportSnapshot(
                pending = transportPending,
                previousAdbEnabled = preferences.getBoolean(PREF_TRANSPORT_PREVIOUS_ADB, false),
                previousWirelessEnabled = preferences.getBoolean(
                    PREF_TRANSPORT_PREVIOUS_WIRELESS_ADB,
                    false
                ),
                currentAdbEnabled = before.adbEnabled,
                currentWirelessEnabled = before.wirelessDebuggingEnabled
            )
            preferences.edit()
                .putBoolean(PREF_TRANSPORT_PREVIOUS_ADB, captured.previousAdbEnabled)
                .putBoolean(PREF_TRANSPORT_PREVIOUS_WIRELESS_ADB, captured.previousWirelessEnabled)
                .putBoolean(PREF_TRANSPORT_RESTORE_PENDING, captured.pending)
                .apply()
        }

        val resolver = context.contentResolver
        return@withContext runCatching {
            // Deliberately DO NOT write development_settings_enabled here.
            // Banking apps may require Developer options to remain visibly OFF.
            applyStellarTransportWrites(
                resolver,
                TransportRestorePolicy.stellarEnableWrites(
                    enableWireless = enableWireless,
                    wirelessDebuggingApiAvailable =
                        android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R
                )
            )

            delay(if (enableWireless) 700L else 400L)

            val after = snapshot(context)
            val success = after.adbEnabled &&
                (!enableWireless || after.wirelessDebuggingEnabled)

            Result(
                success,
                after,
                when {
                    success && enableWireless -> "adb_and_wireless_enabled_without_developer_options"
                    success -> "adb_enabled_without_developer_options"
                    enableWireless -> "Android did not retain ADB / wireless debugging"
                    else -> "Android did not retain ADB_ENABLED"
                }
            )
        }.getOrElse { error ->
            Result(false, snapshot(context), error.message ?: error.javaClass.simpleName)
        }
    }

    /**
     * Restores only the ADB transport switches changed by automatic recovery.
     * Developer options itself is never modified here.
     */
    suspend fun restoreAdbTransportAfterRecovery(context: Context): Result =
        withContext(Dispatchers.IO) {
            if (!hasWriteSecureSettings(context)) {
                return@withContext Result(
                    false,
                    snapshot(context),
                    "WRITE_SECURE_SETTINGS is not granted"
                )
            }

            val preferences = ShizukuSettings.getPreferences()
            val current = snapshot(context)
            val decision = TransportRestorePolicy.decideDesiredTransportRestore(
                developerOptionsEnabled = current.developerOptionsEnabled,
                transportRestorePending = preferences.getBoolean(PREF_TRANSPORT_RESTORE_PENDING, false),
                previousAdbEnabled = preferences.getBoolean(PREF_TRANSPORT_PREVIOUS_ADB, false),
                previousWirelessEnabled = preferences.getBoolean(
                    PREF_TRANSPORT_PREVIOUS_WIRELESS_ADB,
                    false
                )
            )

            // Stellar-compatible cleanup:
            // keep the internal ADB transport enabled while Developer options
            // remain visually OFF, and disable only Wireless debugging after
            // Binder recovery. This is the behavior proven stable on the HONOR 200.
            if (!decision.apply) {
                return@withContext Result(true, current, decision.skipDetail)
            }

            val resolver = context.contentResolver
            val developerOptionsOff = !current.developerOptionsEnabled

            return@withContext runCatching {
                applyStellarTransportWrites(resolver, decision.writes)

                delay(350L)
                val after = snapshot(context)
                val success = TransportRestorePolicy.shouldClearTransportPending(
                    desiredAdbEnabled = decision.adbEnabled,
                    desiredWirelessEnabled = decision.wirelessEnabled,
                    observedAdbEnabled = after.adbEnabled,
                    observedWirelessEnabled = after.wirelessDebuggingEnabled
                )

                if (success) {
                    preferences.edit()
                        .putBoolean(PREF_TRANSPORT_RESTORE_PENDING, false)
                        .apply()
                }

                Result(
                    success,
                    after,
                    TransportRestorePolicy.restoreResultDetail(success, developerOptionsOff)
                )
            }.getOrElse { error ->
                Result(false, snapshot(context), error.message ?: error.javaClass.simpleName)
            }
        }

    suspend fun enableForRecovery(context: Context): Result = withContext(Dispatchers.IO) {
        if (!hasWriteSecureSettings(context)) {
            return@withContext Result(
                false,
                snapshot(context),
                "WRITE_SECURE_SETTINGS is not granted"
            )
        }

        val resolver = context.contentResolver
        return@withContext runCatching {
            // Recovery needs all three switches ON regardless of the pre-disable
            // combination, otherwise adbd may still be unavailable.
            Settings.Global.putInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, 1)
            Settings.Global.putInt(resolver, ADB_ENABLED, 1)
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, 1)
            Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0L)

            delay(400L)

            val after = snapshot(context)
            val success = after.developerOptionsEnabled &&
                after.adbEnabled &&
                after.wirelessDebuggingEnabled

            if (success) {
                ShizukuSettings.getPreferences().edit()
                    .putBoolean(PREF_RESTORE_PENDING, false)
                    .apply()
            }

            Result(
                success,
                snapshot(context),
                if (success) "enabled_for_recovery"
                else "Settings.Global did not retain all required recovery switches"
            )
        }.getOrElse { error ->
            Result(false, snapshot(context), error.message ?: error.javaClass.simpleName)
        }
    }

    suspend fun restore(context: Context): Result = withContext(Dispatchers.IO) {
        if (!hasWriteSecureSettings(context)) {
            return@withContext Result(
                false,
                snapshot(context),
                "WRITE_SECURE_SETTINGS is not granted"
            )
        }

        val preferences = ShizukuSettings.getPreferences()
        val hasSavedState = preferences.getBoolean(PREF_RESTORE_PENDING, false)

        // If Nightzuku itself disabled Developer options, restore exactly what
        // was active before. If there is no saved state, enable the ADB path
        // needed by Nightzuku rather than guessing an unrelated configuration.
        val developerEnabled = if (hasSavedState) {
            preferences.getBoolean(PREF_PREVIOUS_DEVELOPER, true)
        } else {
            true
        }
        val adbEnabled = if (hasSavedState) {
            preferences.getBoolean(PREF_PREVIOUS_ADB, true)
        } else {
            true
        }
        val wirelessEnabled = if (hasSavedState) {
            preferences.getBoolean(PREF_PREVIOUS_WIRELESS_ADB, true)
        } else {
            true
        }

        val resolver = context.contentResolver
        return@withContext runCatching {
            Settings.Global.putInt(resolver, DEVELOPMENT_SETTINGS_ENABLED, if (developerEnabled) 1 else 0)
            Settings.Global.putInt(resolver, ADB_ENABLED, if (adbEnabled) 1 else 0)
            Settings.Global.putInt(resolver, ADB_WIFI_ENABLED, if (wirelessEnabled) 1 else 0)
            if (wirelessEnabled) {
                Settings.Global.putLong(resolver, ADB_ALLOWED_CONNECTION_TIME, 0L)
            }

            // Give adbd / SettingsProvider a moment before the verification read.
            delay(400L)

            val after = snapshot(context)
            val success = after.developerOptionsEnabled == developerEnabled &&
                after.adbEnabled == adbEnabled &&
                after.wirelessDebuggingEnabled == wirelessEnabled

            if (success) {
                preferences.edit().putBoolean(PREF_RESTORE_PENDING, false).apply()
            }

            Result(
                success,
                snapshot(context),
                if (success) "restored" else "Settings.Global did not retain the requested ON state"
            )
        }.getOrElse { error ->
            Result(false, snapshot(context), error.message ?: error.javaClass.simpleName)
        }
    }

    private fun applyStellarTransportWrites(
        resolver: android.content.ContentResolver,
        writes: List<TransportRestorePolicy.GlobalWrite>
    ) {
        for (write in writes) {
            check(write.key != TransportRestorePolicy.DEVELOPMENT_SETTINGS_ENABLED) {
                "Stellar transport path must not write development_settings_enabled"
            }
            when {
                write.intValue != null -> Settings.Global.putInt(resolver, write.key, write.intValue)
                write.longValue != null -> Settings.Global.putLong(resolver, write.key, write.longValue)
            }
        }
    }

    private fun hasWriteSecureSettings(context: Context): Boolean {
        return context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) ==
            PackageManager.PERMISSION_GRANTED
    }

    private fun readGlobalBoolean(
        resolver: android.content.ContentResolver,
        key: String
    ): Boolean {
        return runCatching { Settings.Global.getInt(resolver, key, 0) == 1 }
            .getOrDefault(false)
    }

    private const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
    private const val ADB_ENABLED = "adb_enabled"
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    private const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    private const val PREF_TRANSPORT_RESTORE_PENDING = "adb_transport_restore_pending"
    private const val PREF_TRANSPORT_PREVIOUS_ADB = "adb_transport_previous_enabled"
    private const val PREF_TRANSPORT_PREVIOUS_WIRELESS_ADB = "adb_transport_previous_wireless_enabled"

    private const val PREF_RESTORE_PENDING = "developer_mode_restore_pending"
    private const val PREF_PREVIOUS_DEVELOPER = "developer_mode_previous_enabled"
    private const val PREF_PREVIOUS_ADB = "developer_mode_previous_adb_enabled"
    private const val PREF_PREVIOUS_WIRELESS_ADB = "developer_mode_previous_wireless_adb_enabled"
}
