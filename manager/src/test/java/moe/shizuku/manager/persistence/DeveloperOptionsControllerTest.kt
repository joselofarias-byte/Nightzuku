package moe.shizuku.manager.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class DeveloperOptionsControllerTest {

    @Test
    fun recoveryRestoresOnlyWhenNightzukuPreparedAState() {
        val base = DeveloperOptionsController.Snapshot(
            writeSecureSettingsGranted = true,
            developerOptionsEnabled = false,
            adbEnabled = false,
            wirelessDebuggingEnabled = false,
            restorePending = true
        )
        assertTrue(DeveloperOptionsController.shouldRestoreForRecovery(base))
    }

    @Test
    fun recoveryDoesNotOverrideManualDebugChoiceWithoutPendingRestore() {
        val manualOff = DeveloperOptionsController.Snapshot(
            writeSecureSettingsGranted = true,
            developerOptionsEnabled = false,
            adbEnabled = false,
            wirelessDebuggingEnabled = false,
            restorePending = false
        )
        assertFalse(DeveloperOptionsController.shouldRestoreForRecovery(manualOff))
    }

    @Test
    fun recoveryCannotRestoreWithoutWriteSecureSettings() {
        val noGrant = DeveloperOptionsController.Snapshot(
            writeSecureSettingsGranted = false,
            developerOptionsEnabled = false,
            adbEnabled = false,
            wirelessDebuggingEnabled = false,
            restorePending = true
        )
        assertFalse(DeveloperOptionsController.shouldRestoreForRecovery(noGrant))
    }

    @Test
    fun recoveryDoesNothingWhenEverythingIsAlreadyEnabled() {
        val enabled = DeveloperOptionsController.Snapshot(
            writeSecureSettingsGranted = true,
            developerOptionsEnabled = true,
            adbEnabled = true,
            wirelessDebuggingEnabled = true,
            restorePending = true
        )
        assertFalse(DeveloperOptionsController.shouldRestoreForRecovery(enabled))
    }
}
