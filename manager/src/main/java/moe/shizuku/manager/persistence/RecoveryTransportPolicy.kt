package moe.shizuku.manager.persistence

/**
 * Transport selection shared by NightDog recovery and the persistence UI.
 *
 * Priority:
 * 1. Live Binder
 * 2. Persistent authenticated local TCP
 * 3. mDNS / Wireless debugging
 * 4. Dynamic loopback Wireless debugging port, protocol-verified when mDNS is unavailable
 * 5. Live system ADB TCP, only when safely detectable
 * 6. Wait / retry
 *
 * A configured-but-dead TCP endpoint must not hide a live mDNS endpoint.
 * An unreachable or network-stale mDNS endpoint must not hide loopback recovery.
 */
enum class RecoveryTransport {
    BINDER_ALIVE,
    PERSISTENT_LOCAL_TCP,
    MDNS_WIRELESS_DEBUGGING,
    DYNAMIC_LOCAL_WIRELESS_ADB,
    SYSTEM_ADB_TCP,
    NONE
}

data class TransportCandidate(
    val kind: RecoveryTransport,
    val host: String? = null,
    val port: Int? = null,
    val configured: Boolean = false,
    val socketReachable: Boolean = false,
    val authenticated: Boolean = false
) {
    val endpoint: String?
        get() = if (!host.isNullOrBlank() && port != null && port in 1..65535) {
            "$host:$port"
        } else {
            null
        }

    val usableForRestart: Boolean
        get() = when (kind) {
            RecoveryTransport.BINDER_ALIVE -> true
            RecoveryTransport.PERSISTENT_LOCAL_TCP,
            RecoveryTransport.SYSTEM_ADB_TCP -> authenticated
            RecoveryTransport.MDNS_WIRELESS_DEBUGGING,
            RecoveryTransport.DYNAMIC_LOCAL_WIRELESS_ADB -> socketReachable || authenticated
            RecoveryTransport.NONE -> false
        }
}

object RecoveryTransportPolicy {

    fun selectForDisplay(
        binderAlive: Boolean,
        persistent: TransportCandidate?,
        mdns: TransportCandidate?,
        systemTcp: TransportCandidate?,
        dynamicLocal: TransportCandidate? = null,
        networkChanged: Boolean = false
    ): TransportCandidate {
        if (binderAlive) return TransportCandidate(RecoveryTransport.BINDER_ALIVE)
        if (networkChanged) {
            persistent?.takeIf { it.usableForRestart && NetworkChangePolicy.isLoopbackHost(it.host) }
                ?.let { return it }
            dynamicLocal?.takeIf { it.usableForRestart && NetworkChangePolicy.isLoopbackHost(it.host) }
                ?.let { return it }
            systemTcp?.takeIf { it.usableForRestart && NetworkChangePolicy.isLoopbackHost(it.host) }
                ?.let { return it }
            mdns?.takeIf { it.socketReachable && NetworkChangePolicy.isLoopbackHost(it.host) }
                ?.let { return it }
            return TransportCandidate(RecoveryTransport.NONE)
        }
        persistent?.takeIf { it.usableForRestart }?.let { return it }
        mdns?.takeIf { it.socketReachable && !it.host.isNullOrBlank() && it.port != null }?.let { return it }
        dynamicLocal?.takeIf { it.usableForRestart }?.let { return it }
        systemTcp?.takeIf { it.usableForRestart }?.let { return it }
        return TransportCandidate(RecoveryTransport.NONE)
    }

    fun selectForRecoveryAttempt(
        persistent: TransportCandidate?,
        mdns: TransportCandidate?,
        systemTcp: TransportCandidate?,
        dynamicLocal: TransportCandidate? = null,
        networkChanged: Boolean = false
    ): TransportCandidate {
        if (networkChanged) {
            firstReachableLoopback(persistent, dynamicLocal, systemTcp, mdns)?.let { return it }
        }
        persistent?.takeIf { it.socketReachable }?.let { return it }
        mdns?.takeIf { it.socketReachable && !it.host.isNullOrBlank() && it.port != null }?.let { return it }
        dynamicLocal?.takeIf { it.socketReachable }?.let { return it }
        systemTcp?.takeIf { it.socketReachable }?.let { return it }
        return TransportCandidate(RecoveryTransport.NONE)
    }

    private fun firstReachableLoopback(vararg candidates: TransportCandidate?): TransportCandidate? {
        return candidates.firstOrNull { candidate ->
            candidate != null &&
                candidate.socketReachable &&
                NetworkChangePolicy.isLoopbackHost(candidate.host)
        }
    }

    fun localTcpRecoveryAvailable(persistent: TransportCandidate?): Boolean {
        return persistent?.usableForRestart == true
    }
}
