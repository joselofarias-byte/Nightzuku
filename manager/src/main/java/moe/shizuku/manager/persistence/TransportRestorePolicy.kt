package moe.shizuku.manager.persistence

/**
 * Pure decisions for the Stellar-aligned ADB transport snapshot used by
 * automatic recovery.
 *
 * Enable and restore never write [DEVELOPMENT_SETTINGS_ENABLED]. Prior ADB and
 * wireless debug are captured once while `adb_transport_restore_pending` is
 * false, so retries keep the original snapshot.
 *
 * After Binder:
 * - Developer options OFF: keep ADB on and wireless debugging off. Turning
 *   ADB_ENABLED back off was unstable on HONOR 200; Stellar leaves it on.
 * - Developer options ON with a pending snapshot: restore that exact pair.
 *
 * Pending is cleared only when Android reflects the requested switches.
 */
object TransportRestorePolicy {

    const val DEVELOPMENT_SETTINGS_ENABLED = "development_settings_enabled"
    const val ADB_ENABLED = "adb_enabled"
    const val ADB_WIFI_ENABLED = "adb_wifi_enabled"
    const val ADB_ALLOWED_CONNECTION_TIME = "adb_allowed_connection_time"

    const val NO_TRANSPORT_RESTORE_PENDING = "no_transport_restore_pending"
    const val DETAIL_STELLAR_READY = "stellar_transport_ready_after_recovery"
    const val DETAIL_TRANSPORT_RESTORED = "transport_restored_after_recovery"
    const val DETAIL_RESTORE_MISMATCH = "Android did not restore the requested ADB transport state"

    data class GlobalWrite(
        val key: String,
        val intValue: Int? = null,
        val longValue: Long? = null
    )

    data class TransportSnapshotState(
        val pending: Boolean,
        val previousAdbEnabled: Boolean,
        val previousWirelessEnabled: Boolean
    )

    data class DesiredTransportRestore(
        val apply: Boolean,
        val adbEnabled: Boolean,
        val wirelessEnabled: Boolean,
        val writes: List<GlobalWrite>,
        val skipDetail: String? = null
    )

    fun shouldCaptureTransportSnapshot(transportRestorePending: Boolean): Boolean {
        return !transportRestorePending
    }

    fun captureTransportSnapshot(
        pending: Boolean,
        previousAdbEnabled: Boolean,
        previousWirelessEnabled: Boolean,
        currentAdbEnabled: Boolean,
        currentWirelessEnabled: Boolean
    ): TransportSnapshotState {
        if (!shouldCaptureTransportSnapshot(pending)) {
            return TransportSnapshotState(
                pending = pending,
                previousAdbEnabled = previousAdbEnabled,
                previousWirelessEnabled = previousWirelessEnabled
            )
        }
        return TransportSnapshotState(
            pending = true,
            previousAdbEnabled = currentAdbEnabled,
            previousWirelessEnabled = currentWirelessEnabled
        )
    }

    /**
     * Writes used to bring adbd back without touching Developer options.
     * Wireless debugging is requested only when the caller asks and the API exists.
     */
    fun stellarEnableWrites(
        enableWireless: Boolean,
        wirelessDebuggingApiAvailable: Boolean
    ): List<GlobalWrite> {
        val writes = mutableListOf(
            GlobalWrite(ADB_ENABLED, intValue = 1),
            GlobalWrite(ADB_ALLOWED_CONNECTION_TIME, longValue = 0L)
        )
        if (enableWireless && wirelessDebuggingApiAvailable) {
            writes += GlobalWrite(ADB_WIFI_ENABLED, intValue = 1)
        }
        return writes
    }

    fun decideDesiredTransportRestore(
        developerOptionsEnabled: Boolean,
        transportRestorePending: Boolean,
        previousAdbEnabled: Boolean,
        previousWirelessEnabled: Boolean
    ): DesiredTransportRestore {
        if (developerOptionsEnabled && !transportRestorePending) {
            return DesiredTransportRestore(
                apply = false,
                adbEnabled = previousAdbEnabled,
                wirelessEnabled = previousWirelessEnabled,
                writes = emptyList(),
                skipDetail = NO_TRANSPORT_RESTORE_PENDING
            )
        }

        val developerOptionsOff = !developerOptionsEnabled
        val adbEnabled = if (developerOptionsOff) true else previousAdbEnabled
        val wirelessEnabled = if (developerOptionsOff) false else previousWirelessEnabled
        return DesiredTransportRestore(
            apply = true,
            adbEnabled = adbEnabled,
            wirelessEnabled = wirelessEnabled,
            writes = restoreWrites(adbEnabled, wirelessEnabled)
        )
    }

    fun shouldClearTransportPending(
        desiredAdbEnabled: Boolean,
        desiredWirelessEnabled: Boolean,
        observedAdbEnabled: Boolean,
        observedWirelessEnabled: Boolean
    ): Boolean {
        return observedAdbEnabled == desiredAdbEnabled &&
            observedWirelessEnabled == desiredWirelessEnabled
    }

    fun restoreResultDetail(verified: Boolean, developerOptionsOff: Boolean): String {
        return when {
            verified && developerOptionsOff -> DETAIL_STELLAR_READY
            verified -> DETAIL_TRANSPORT_RESTORED
            else -> DETAIL_RESTORE_MISMATCH
        }
    }

    fun writesDevelopmentSettings(writes: List<GlobalWrite>): Boolean {
        return writes.any { it.key == DEVELOPMENT_SETTINGS_ENABLED }
    }

    private fun restoreWrites(
        adbEnabled: Boolean,
        wirelessEnabled: Boolean
    ): List<GlobalWrite> {
        val writes = mutableListOf(
            GlobalWrite(ADB_WIFI_ENABLED, intValue = if (wirelessEnabled) 1 else 0),
            GlobalWrite(ADB_ENABLED, intValue = if (adbEnabled) 1 else 0)
        )
        if (wirelessEnabled) {
            writes += GlobalWrite(ADB_ALLOWED_CONNECTION_TIME, longValue = 0L)
        }
        return writes
    }
}
