package moe.shizuku.manager.adb

import moe.shizuku.manager.ShizukuSettings

/**
 * Resolves the endpoint used to start Shizuku without duplicating transport policy.
 *
 * Priority for normal startup:
 * 1. Explicit persistent TCP endpoint enabled by the user.
 * 2. Current mDNS endpoint advertised by wireless debugging.
 * 3. Endpoint supplied by the caller.
 *
 * The system ADB TCP endpoint is exposed separately for diagnostics/recovery tests. It must not
 * silently replace the user's normal transport policy.
 */
internal object AdbTransportResolver {

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val GETPROP = "/system/bin/getprop"
    private const val SERVICE_ADB_TCP_PORT = "service.adb.tcp.port"

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

    /**
     * Returns Android's currently configured local ADB-over-TCP endpoint, when present.
     *
     * `service.adb.tcp.port` describes the live adbd TCP configuration and is intentionally kept
     * separate from Nightzuku's persistent-TCP preference. Callers still have to authenticate the
     * returned endpoint with [AdbClient] before treating it as usable.
     */
    fun systemAdbTcpEndpoint(): AdbEndpoint? {
        val process = runCatching {
            ProcessBuilder(GETPROP, SERVICE_ADB_TCP_PORT)
                .redirectErrorStream(true)
                .start()
        }.getOrNull() ?: return null

        return try {
            val value = process.inputStream.bufferedReader().use { reader ->
                reader.readLine()?.trim()
            }
            if (process.waitFor() != 0) return null

            val port = value?.toIntOrNull() ?: return null
            if (port !in 1..65535) return null

            AdbEndpoint(LOOPBACK_HOST, port)
        } catch (_: Exception) {
            null
        } finally {
            process.destroy()
        }
    }
}
