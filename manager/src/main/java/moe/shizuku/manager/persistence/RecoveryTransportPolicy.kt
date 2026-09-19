package moe.shizuku.manager.persistence

/**
 * Transport selection shared by NightDog recovery and the persistence UI.
 *
 * Priority:
 * 1. Live Binder
 * 2. Persistent authenticated local TCP
 * 3. mDNS / Wireless debugging
 * 4. Live system ADB TCP, only when safely detectable
 * 5. Wait / retry
 *
 * A configured-but-dead TCP endpoint must not hide a live mDNS endpoint.
 */
enum class RecoveryTransport {
    BINDER_ALIVE,
    PERSISTENT_LOCAL_TCP,
    MDNS_WIRELESS_DEBUGGING,
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
            RecoveryTransport.MDNS_WIRELESS_DEBUGGING -> socketReachable || authenticated
            RecoveryTransport.NONE -> false
        }
}

object RecoveryTransportPolicy {

    fun selectForDisplay(
        binderAlive: Boolean,
        persistent: TransportCandidate?,
        mdns: TransportCandidate?,
        systemTcp: TransportCandidate?
    ): TransportCandidate {
        if (binderAlive) return TransportCandidate(RecoveryTransport.BINDER_ALIVE)
        persistent?.takeIf { it.usableForRestart }?.let { return it }
        mdns?.takeIf { !it.host.isNullOrBlank() && it.port != null }?.let { return it }
        systemTcp?.takeIf { it.usableForRestart }?.let { return it }
        return TransportCandidate(RecoveryTransport.NONE)
    }

    fun selectForRecoveryAttempt(
        persistent: TransportCandidate?,
        mdns: TransportCandidate?,
        systemTcp: TransportCandidate?
    ): TransportCandidate {
        persistent?.takeIf { it.socketReachable }?.let { return it }
        mdns?.takeIf { !it.host.isNullOrBlank() && it.port != null }?.let { return it }
        systemTcp?.takeIf { it.socketReachable }?.let { return it }
        return TransportCandidate(RecoveryTransport.NONE)
    }

    fun localTcpRecoveryAvailable(persistent: TransportCandidate?): Boolean {
        return persistent?.usableForRestart == true
    }
}
