package moe.shizuku.manager.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Changes adbd transport mode through the daemon's official restart services.
 * The Nightzuku preference is committed only after the requested endpoint is verified.
 */
object AdbTcpController {

    data class Result(
        val success: Boolean,
        val message: String
    )

    suspend fun enable(host: String, port: Int): Result = withContext(Dispatchers.IO) {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty() || port !in 1..65535) {
            return@withContext Result(false, "Invalid TCP host or port")
        }

        val source = AdbMdns.getDiscoveredEndpoint(AdbMdns.TLS_CONNECT)
            ?: return@withContext Result(false, "Wireless debugging endpoint is not available")

        // adbd closes the authenticated connection while switching transport.
        // Reachability of the requested endpoint is the authoritative result.
        runCatching { executeService(source, "tcpip:$port") }

        if (!awaitReachable(normalizedHost, port)) {
            return@withContext Result(
                false,
                "TCP port did not become reachable; Nightzuku did not save the endpoint"
            )
        }

        if (!ShizukuSettings.setAdbTcpEndpoint(true, normalizedHost, port)) {
            return@withContext Result(false, "Unable to save TCP endpoint")
        }

        Result(true, "ADB TCP enabled at $normalizedHost:$port")
    }

    suspend fun disable(): Result = withContext(Dispatchers.IO) {
        val tcpEndpoint = AdbTransportResolver.persistentTcpEndpoint()
            ?: run {
                ShizukuSettings.setAdbTcpEnabled(false)
                return@withContext Result(true, "ADB TCP preference disabled")
            }

        runCatching { executeService(tcpEndpoint, "usb:") }

        delay(RESTART_SETTLE_MS)
        if (isReachable(tcpEndpoint.host, tcpEndpoint.port)) {
            return@withContext Result(false, "TCP endpoint is still reachable; setting was preserved")
        }

        ShizukuSettings.setAdbTcpEnabled(false)
        Result(true, "ADB TCP disabled")
    }

    private fun executeService(endpoint: AdbEndpoint, service: String) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        AdbClient(endpoint.host, endpoint.port, key).use { client ->
            client.connect()
            client.serviceCommand(service)
        }
    }

    private suspend fun awaitReachable(host: String, port: Int): Boolean {
        repeat(VERIFY_ATTEMPTS) {
            delay(VERIFY_INTERVAL_MS)
            if (isReachable(host, port)) return true
        }
        return false
    }

    private fun isReachable(host: String, port: Int): Boolean = runCatching {
        Socket().use { socket ->
            socket.connect(InetSocketAddress(host, port), CONNECT_TIMEOUT_MS)
        }
        true
    }.getOrDefault(false)

    private const val VERIFY_ATTEMPTS = 8
    private const val VERIFY_INTERVAL_MS = 1_000L
    private const val RESTART_SETTLE_MS = 2_000L
    private const val CONNECT_TIMEOUT_MS = 1_000
}
