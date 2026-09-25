package moe.shizuku.manager.persistence

enum class PersistenceServiceState {
    RUNNING,
    RECOVERING,
    WAITING_FOR_ADB,
    ERROR,
    MANUALLY_STOPPED
}

data class PersistenceUiModel(
    val service: PersistenceServiceState,
    val desiredRunning: Boolean,
    val transport: RecoveryTransport,
    val endpoint: String?,
    val tcp: TcpHealth,
    val serverPid: Int?,
    val recoveryCount: Int,
    val lastResultKey: String,
    val lastFailure: String?,
    val retryRemainingMs: Long,
    val localTcpRecoveryAvailable: Boolean,
    val wirelessActivationRequired: Boolean,
    val networkChangePending: Boolean = false
)

object PersistenceUiMapper {

    fun mapService(
        desiredRunning: Boolean,
        binderAlive: Boolean,
        stage: String
    ): PersistenceServiceState {
        if (!desiredRunning || stage == "MANUALLY_STOPPED") {
            return PersistenceServiceState.MANUALLY_STOPPED
        }
        if (binderAlive || stage == "RUNNING") {
            return PersistenceServiceState.RUNNING
        }
        if (stage == "ERROR") {
            return PersistenceServiceState.ERROR
        }
        if (stage == "WAITING_FOR_ADB") {
            return PersistenceServiceState.WAITING_FOR_ADB
        }
        return PersistenceServiceState.RECOVERING
    }

    fun map(
        desiredRunning: Boolean,
        binderAlive: Boolean,
        stage: String,
        snapshotEndpoint: String?,
        serverPid: Int?,
        recoveryCount: Int,
        lastResultKey: String,
        lastFailure: String?,
        retryRemainingMs: Long,
        reactivationRequired: Boolean,
        tcp: TcpHealth,
        displayTransport: TransportCandidate,
        networkChangePending: Boolean = false
    ): PersistenceUiModel {
        val service = mapService(desiredRunning, binderAlive, stage)
        val localTcpRecoveryAvailable = tcp.usableForRestart
        val wirelessActivationRequired = !binderAlive &&
            !localTcpRecoveryAvailable &&
            (reactivationRequired || displayTransport.kind == RecoveryTransport.NONE)

        val endpoint = visibleEndpoint(
            networkChangePending = networkChangePending,
            displayTransport = displayTransport,
            tcp = tcp,
            snapshotEndpoint = snapshotEndpoint
        )

        return PersistenceUiModel(
            service = service,
            desiredRunning = desiredRunning,
            transport = displayTransport.kind,
            endpoint = endpoint,
            tcp = tcp,
            serverPid = serverPid,
            recoveryCount = recoveryCount,
            lastResultKey = lastResultKey,
            lastFailure = lastFailure,
            retryRemainingMs = retryRemainingMs.coerceAtLeast(0L),
            localTcpRecoveryAvailable = localTcpRecoveryAvailable,
            wirelessActivationRequired = wirelessActivationRequired,
            networkChangePending = networkChangePending
        )
    }

    private fun visibleEndpoint(
        networkChangePending: Boolean,
        displayTransport: TransportCandidate,
        tcp: TcpHealth,
        snapshotEndpoint: String?
    ): String? {
        if (networkChangePending) {
            displayTransport.endpoint
                ?.takeIf { NetworkChangePolicy.isLoopbackHost(NetworkChangePolicy.hostOf(it)) }
                ?.let { return it }
            if (tcp.usableForRestart) {
                tcp.endpoint
                    ?.takeIf { NetworkChangePolicy.isLoopbackHost(NetworkChangePolicy.hostOf(it)) }
                    ?.let { return it }
            }
            return null
        }
        return when {
            displayTransport.kind == RecoveryTransport.BINDER_ALIVE ->
                tcp.endpoint ?: snapshotEndpoint?.takeUnless { it == "not required" }
            !displayTransport.endpoint.isNullOrBlank() -> displayTransport.endpoint
            !tcp.endpoint.isNullOrBlank() -> tcp.endpoint
            else -> snapshotEndpoint
        }
    }
}
