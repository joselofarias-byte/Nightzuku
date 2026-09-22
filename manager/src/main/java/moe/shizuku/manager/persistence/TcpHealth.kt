package moe.shizuku.manager.persistence

enum class TcpHealthState {
    DISABLED,
    UNKNOWN,
    ENABLED_UNREACHABLE,
    ENABLED_REACHABLE
}

enum class TcpCapability {
    NOT_CONFIGURED,
    CONFIGURED,
    SOCKET_REACHABLE,
    ADB_AUTHENTICATED,
    USABLE_FOR_RESTART
}

data class TcpHealth(
    val state: TcpHealthState,
    val capability: TcpCapability,
    val endpoint: String? = null
) {
    val usableForRestart: Boolean
        get() = capability == TcpCapability.USABLE_FOR_RESTART
}

object TcpHealthClassifier {

    fun classify(
        enabled: Boolean,
        host: String?,
        port: Int?,
        socketReachable: Boolean?,
        authenticated: Boolean?
    ): TcpHealth {
        val configured = !host.isNullOrBlank() && port != null && port in 1..65535
        val endpoint = if (configured) "$host:$port" else null

        if (!enabled) {
            return TcpHealth(
                state = TcpHealthState.DISABLED,
                capability = if (configured) TcpCapability.CONFIGURED else TcpCapability.NOT_CONFIGURED,
                endpoint = endpoint
            )
        }

        if (!configured) {
            return TcpHealth(
                state = TcpHealthState.UNKNOWN,
                capability = TcpCapability.NOT_CONFIGURED
            )
        }

        val capability = when {
            authenticated == true -> TcpCapability.USABLE_FOR_RESTART
            socketReachable == true -> TcpCapability.SOCKET_REACHABLE
            else -> TcpCapability.CONFIGURED
        }

        val state = when {
            authenticated == true || socketReachable == true -> TcpHealthState.ENABLED_REACHABLE
            socketReachable == false -> TcpHealthState.ENABLED_UNREACHABLE
            else -> TcpHealthState.UNKNOWN
        }

        return TcpHealth(state = state, capability = capability, endpoint = endpoint)
    }
}
