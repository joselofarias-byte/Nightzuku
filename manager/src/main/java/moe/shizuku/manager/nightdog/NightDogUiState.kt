package moe.shizuku.manager.nightdog

import moe.shizuku.manager.ShizukuSettings

enum class NightDogTrigger {
    APP_START,
    SYSTEM_BOOT,
    AUTO_RECOVERY,
    MANUAL_START,
    MANUAL_TEST
}

enum class NightDogPhase {
    IDLE,
    CHECKING,
    STARTING,
    WAITING_ADB,
    WAITING_SERVER,
    RECOVERING,
    SUCCESS,
    ERROR
}

enum class NightDogTestPhase {
    IDLE,
    PREPARING,
    CONFIRMING,
    STOPPING,
    SERVER_DOWN,
    WAITING_NIGHTDOG,
    VERIFYING,
    RECOVERED,
    ERROR,
    UNAVAILABLE
}

data class NightDogUiState(
    val serverOnline: Boolean = false,
    val mode: Int = ShizukuSettings.LaunchMethod.UNKNOWN,
    val serverPid: Int? = null,
    val recoveryCount: Int = 0,
    val lastRecoveryAt: Long = 0L,

    // Operational state machine
    val phase: NightDogPhase = NightDogPhase.IDLE,
    val trigger: NightDogTrigger? = null,
    val progressMessage: String? = null,
    val operationStartTime: Long = 0L,
    val oldPid: Int? = null,
    val newPid: Int? = null,
    val attempt: Int = 0,
    val errorMessage: String? = null,

    // Test state machine
    val testPhase: NightDogTestPhase = NightDogTestPhase.IDLE,
    val testOldPid: Int? = null,
    val testNewPid: Int? = null,
    val testElapsedMs: Long? = null,
    val testMessage: String? = null
) {
    val isOperationInProgress: Boolean
        get() = phase in setOf(
            NightDogPhase.CHECKING,
            NightDogPhase.STARTING,
            NightDogPhase.WAITING_ADB,
            NightDogPhase.WAITING_SERVER,
            NightDogPhase.RECOVERING
        )

    val isTestInProgress: Boolean
        get() = testPhase in setOf(
            NightDogTestPhase.PREPARING,
            NightDogTestPhase.STOPPING,
            NightDogTestPhase.SERVER_DOWN,
            NightDogTestPhase.WAITING_NIGHTDOG,
            NightDogTestPhase.VERIFYING,
            NightDogTestPhase.CONFIRMING
        )

    val isAnyBusy: Boolean
        get() = isOperationInProgress || isTestInProgress

    val bulldogState: NightDogBulldogState
        get() = when {
            phase == NightDogPhase.ERROR || testPhase == NightDogTestPhase.ERROR || testPhase == NightDogTestPhase.UNAVAILABLE ->
                NightDogBulldogState.ERROR
            phase == NightDogPhase.SUCCESS || testPhase == NightDogTestPhase.RECOVERED ->
                NightDogBulldogState.SUCCESS
            phase in setOf(NightDogPhase.STARTING, NightDogPhase.WAITING_ADB, NightDogPhase.WAITING_SERVER, NightDogPhase.RECOVERING) ||
            testPhase in setOf(NightDogTestPhase.WAITING_NIGHTDOG, NightDogTestPhase.VERIFYING) ->
                NightDogBulldogState.RECOVERING
            testPhase in setOf(NightDogTestPhase.STOPPING, NightDogTestPhase.SERVER_DOWN) ->
                NightDogBulldogState.ALERT
            !serverOnline && phase == NightDogPhase.IDLE ->
                NightDogBulldogState.ALERT
            else -> NightDogBulldogState.NORMAL
        }
}

enum class NightDogBulldogState {
    NORMAL,
    ALERT,
    RECOVERING,
    SUCCESS,
    ERROR
}
