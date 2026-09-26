package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Snapshot/restore decisions for automatic ADB recovery.
 * These cases do not touch Settings or Robolectric.
 */
class TransportRestorePolicyTest {

    @Test
    fun priorAdbOffWirelessOff_captureOnce_devOffKeepsAdbOnAndWirelessOff() {
        val captured = captureOnce(currentAdb = false, currentWireless = false)
        assertTrue(captured.pending)
        assertFalse(captured.previousAdbEnabled)
        assertFalse(captured.previousWirelessEnabled)

        val retry = TransportRestorePolicy.captureTransportSnapshot(
            pending = captured.pending,
            previousAdbEnabled = captured.previousAdbEnabled,
            previousWirelessEnabled = captured.previousWirelessEnabled,
            currentAdbEnabled = true,
            currentWirelessEnabled = true
        )
        assertEquals(captured, retry)

        val restore = TransportRestorePolicy.decideDesiredTransportRestore(
            developerOptionsEnabled = false,
            transportRestorePending = captured.pending,
            previousAdbEnabled = captured.previousAdbEnabled,
            previousWirelessEnabled = captured.previousWirelessEnabled
        )
        assertTrue(restore.apply)
        assertTrue(restore.adbEnabled)
        assertFalse(restore.wirelessEnabled)
        assertFalse(TransportRestorePolicy.writesDevelopmentSettings(restore.writes))
        assertEquals(
            listOf(
                TransportRestorePolicy.GlobalWrite(TransportRestorePolicy.ADB_WIFI_ENABLED, intValue = 0),
                TransportRestorePolicy.GlobalWrite(TransportRestorePolicy.ADB_ENABLED, intValue = 1)
            ),
            restore.writes
        )

        val devOnExact = restore(captured, developerOptionsEnabled = true)
        assertFalse(devOnExact.adbEnabled)
        assertFalse(devOnExact.wirelessEnabled)
        assertEquals(0, devOnExact.writes.single { it.key == TransportRestorePolicy.ADB_ENABLED }.intValue)

        assertTrue(
            TransportRestorePolicy.shouldClearTransportPending(
                desiredAdbEnabled = true,
                desiredWirelessEnabled = false,
                observedAdbEnabled = true,
                observedWirelessEnabled = false
            )
        )
        assertTrue(pendingAfterVerification(pending = true, verified = false))
        assertFalse(
            pendingAfterVerification(
                pending = true,
                verified = TransportRestorePolicy.shouldClearTransportPending(
                    desiredAdbEnabled = true,
                    desiredWirelessEnabled = false,
                    observedAdbEnabled = true,
                    observedWirelessEnabled = false
                )
            )
        )
    }

    @Test
    fun priorAdbOnWirelessOff_devOnRestoresExactSnapshot() {
        val captured = captureOnce(currentAdb = true, currentWireless = false)
        assertTrue(captured.previousAdbEnabled)
        assertFalse(captured.previousWirelessEnabled)

        val devOff = restore(captured, developerOptionsEnabled = false)
        assertTrue(devOff.adbEnabled)
        assertFalse(devOff.wirelessEnabled)

        val devOn = restore(captured, developerOptionsEnabled = true)
        assertTrue(devOn.apply)
        assertTrue(devOn.adbEnabled)
        assertFalse(devOn.wirelessEnabled)
        assertFalse(TransportRestorePolicy.writesDevelopmentSettings(devOn.writes))
        assertEquals(2, devOn.writes.size)
        assertEquals(0, devOn.writes.single { it.key == TransportRestorePolicy.ADB_WIFI_ENABLED }.intValue)
        assertEquals(1, devOn.writes.single { it.key == TransportRestorePolicy.ADB_ENABLED }.intValue)
    }

    @Test
    fun priorAdbOnWirelessOn_devOnRestoresBoth_devOffStillForcesWirelessOff() {
        val captured = captureOnce(currentAdb = true, currentWireless = true)

        val devOn = restore(captured, developerOptionsEnabled = true)
        assertTrue(devOn.adbEnabled)
        assertTrue(devOn.wirelessEnabled)
        assertEquals(
            listOf(
                TransportRestorePolicy.GlobalWrite(TransportRestorePolicy.ADB_WIFI_ENABLED, intValue = 1),
                TransportRestorePolicy.GlobalWrite(TransportRestorePolicy.ADB_ENABLED, intValue = 1),
                TransportRestorePolicy.GlobalWrite(
                    TransportRestorePolicy.ADB_ALLOWED_CONNECTION_TIME,
                    longValue = 0L
                )
            ),
            devOn.writes
        )
        assertFalse(TransportRestorePolicy.writesDevelopmentSettings(devOn.writes))

        val devOff = restore(captured, developerOptionsEnabled = false)
        assertTrue(devOff.adbEnabled)
        assertFalse(devOff.wirelessEnabled)
        assertEquals(
            TransportRestorePolicy.DETAIL_STELLAR_READY,
            TransportRestorePolicy.restoreResultDetail(verified = true, developerOptionsOff = true)
        )
    }

    @Test
    fun existingPending_secondEnableDoesNotOverwriteOriginalSnapshot() {
        val original = TransportRestorePolicy.captureTransportSnapshot(
            pending = false,
            previousAdbEnabled = false,
            previousWirelessEnabled = false,
            currentAdbEnabled = false,
            currentWirelessEnabled = true
        )
        assertTrue(TransportRestorePolicy.shouldCaptureTransportSnapshot(false))
        assertFalse(TransportRestorePolicy.shouldCaptureTransportSnapshot(original.pending))

        val second = TransportRestorePolicy.captureTransportSnapshot(
            pending = original.pending,
            previousAdbEnabled = original.previousAdbEnabled,
            previousWirelessEnabled = original.previousWirelessEnabled,
            currentAdbEnabled = true,
            currentWirelessEnabled = false
        )
        assertEquals(false, second.previousAdbEnabled)
        assertEquals(true, second.previousWirelessEnabled)
        assertEquals(original, second)
    }

    @Test
    fun mismatchedOrFailedRestoreKeepsPending() {
        val desiredAdb = true
        val desiredWireless = false
        assertFalse(
            TransportRestorePolicy.shouldClearTransportPending(
                desiredAdbEnabled = desiredAdb,
                desiredWirelessEnabled = desiredWireless,
                observedAdbEnabled = false,
                observedWirelessEnabled = false
            )
        )
        assertFalse(
            TransportRestorePolicy.shouldClearTransportPending(
                desiredAdbEnabled = desiredAdb,
                desiredWirelessEnabled = desiredWireless,
                observedAdbEnabled = true,
                observedWirelessEnabled = true
            )
        )
        assertTrue(pendingAfterVerification(pending = true, verified = false))
        assertEquals(
            TransportRestorePolicy.DETAIL_RESTORE_MISMATCH,
            TransportRestorePolicy.restoreResultDetail(verified = false, developerOptionsOff = true)
        )
        assertEquals(
            TransportRestorePolicy.DETAIL_TRANSPORT_RESTORED,
            TransportRestorePolicy.restoreResultDetail(verified = true, developerOptionsOff = false)
        )
    }

    @Test
    fun stellarEnableAndRestoreNeverRequestDevelopmentSettings() {
        val adbOnly = TransportRestorePolicy.stellarEnableWrites(
            enableWireless = false,
            wirelessDebuggingApiAvailable = true
        )
        val withWireless = TransportRestorePolicy.stellarEnableWrites(
            enableWireless = true,
            wirelessDebuggingApiAvailable = true
        )
        val wirelessUnavailable = TransportRestorePolicy.stellarEnableWrites(
            enableWireless = true,
            wirelessDebuggingApiAvailable = false
        )
        assertEquals(
            listOf(
                TransportRestorePolicy.GlobalWrite(TransportRestorePolicy.ADB_ENABLED, intValue = 1),
                TransportRestorePolicy.GlobalWrite(
                    TransportRestorePolicy.ADB_ALLOWED_CONNECTION_TIME,
                    longValue = 0L
                )
            ),
            adbOnly
        )
        assertEquals(TransportRestorePolicy.ADB_WIFI_ENABLED, withWireless.last().key)
        assertEquals(1, withWireless.last().intValue)
        assertFalse(wirelessUnavailable.any { it.key == TransportRestorePolicy.ADB_WIFI_ENABLED })

        val devOff = TransportRestorePolicy.decideDesiredTransportRestore(
            developerOptionsEnabled = false,
            transportRestorePending = true,
            previousAdbEnabled = false,
            previousWirelessEnabled = false
        )
        val devOn = TransportRestorePolicy.decideDesiredTransportRestore(
            developerOptionsEnabled = true,
            transportRestorePending = true,
            previousAdbEnabled = false,
            previousWirelessEnabled = false
        )
        listOf(adbOnly, withWireless, wirelessUnavailable, devOff.writes, devOn.writes).forEach { writes ->
            assertFalse(TransportRestorePolicy.writesDevelopmentSettings(writes))
            assertTrue(writes.none { it.key == TransportRestorePolicy.DEVELOPMENT_SETTINGS_ENABLED })
        }
    }

    @Test
    fun devOnWithoutPendingDoesNotWrite_devOffWithoutPendingStillAppliesStellarCleanup() {
        val skipped = TransportRestorePolicy.decideDesiredTransportRestore(
            developerOptionsEnabled = true,
            transportRestorePending = false,
            previousAdbEnabled = true,
            previousWirelessEnabled = true
        )
        assertFalse(skipped.apply)
        assertTrue(skipped.writes.isEmpty())
        assertEquals(TransportRestorePolicy.NO_TRANSPORT_RESTORE_PENDING, skipped.skipDetail)

        val devOff = TransportRestorePolicy.decideDesiredTransportRestore(
            developerOptionsEnabled = false,
            transportRestorePending = false,
            previousAdbEnabled = false,
            previousWirelessEnabled = true
        )
        assertTrue(devOff.apply)
        assertTrue(devOff.adbEnabled)
        assertFalse(devOff.wirelessEnabled)
        assertFalse(TransportRestorePolicy.writesDevelopmentSettings(devOff.writes))
    }

    private fun captureOnce(currentAdb: Boolean, currentWireless: Boolean) =
        TransportRestorePolicy.captureTransportSnapshot(
            pending = false,
            previousAdbEnabled = true,
            previousWirelessEnabled = true,
            currentAdbEnabled = currentAdb,
            currentWirelessEnabled = currentWireless
        )

    private fun restore(
        captured: TransportRestorePolicy.TransportSnapshotState,
        developerOptionsEnabled: Boolean
    ) = TransportRestorePolicy.decideDesiredTransportRestore(
        developerOptionsEnabled = developerOptionsEnabled,
        transportRestorePending = captured.pending,
        previousAdbEnabled = captured.previousAdbEnabled,
        previousWirelessEnabled = captured.previousWirelessEnabled
    )

    /** Mirrors DeveloperOptionsController: pending is cleared only after a match. */
    private fun pendingAfterVerification(pending: Boolean, verified: Boolean): Boolean {
        return if (verified) false else pending
    }
}
