package moe.shizuku.manager.adb

import moe.shizuku.manager.ShizukuSettings

/**
 * Resolves the endpoint used to start Shizuku without duplicating transport policy.
 *
 * Priority:
 * 1. Explicit persistent TCP endpoint enabled by the user.
 * 2. Current mDNS endpoint advertised by wireless debugging.
 * 3. Endpoint supplied by the caller.
 */
internal object AdbTransportResolver {

    private const val LOOPBACK_HOST = "127.0.0.1"

    fun resolve(
        requestedHost: String?,
        requestedPort: Int,
        serviceType: String = AdbMdns.TLS_CONNECT
    ): AdbEndpoint {
        persistentTcpEndpoint()?.let { return it }

        val discovered = AdbMdns.getResolvedEndpoint(serviceType)
        if (discovered != null && (requestedHost.isNullOrBlank() || requestedHost == LOOPBACK_HOST)) {
            return discovered
        }

        val host = requestedHost?.takeIf { it.isNotBlank() } ?: LOOPBACK_HOST
        require(requestedPort in 1..65535) { "Invalid ADB port: $requestedPort" }
        return AdbEndpoint(host, requestedPort)
    }

    fun persistentTcpEndpoint(): AdbEndpoint? {
        if (!ShizukuSettings.isAdbTcpEnabled()) return null

        val host = ShizukuSettings.getAdbTcpHost().trim()
        val port = ShizukuSettings.getAdbTcpPort()
        if (host.isEmpty() || port !in 1..65535) return null

        return AdbEndpoint(host, port)
    }
}
