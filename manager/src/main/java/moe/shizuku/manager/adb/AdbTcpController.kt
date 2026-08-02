package moe.shizuku.manager.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Changes adbd TCP mode through an already authenticated wireless-debugging session.
 * The preference is committed only after the requested endpoint becomes reachable.
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

        val command = buildString {
            append("setprop service.adb.tcp.port ").append(port).append("; ")
            append("setprop persist.adb.tcp.port ").append(port).append("; ")
            append("stop adbd; start adbd")
        }

        runCatching { execute(source, command) }
            .onFailure {
                return@withContext Result(false, it.message ?: "Unable to request TCP mode")
            }

        if (!awaitReachable(normalizedHost, port)) {
            return@withContext Result(false, "TCP port did not become reachable")
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

        val command = buildString {
            append("setprop service.adb.tcp.port -1; ")
            append("setprop persist.adb.tcp.port -1; ")
            append("stop adbd; start adbd")
        }

        runCatching { execute(tcpEndpoint, command) }
            .onFailure {
                return@withContext Result(false, it.message ?: "Unable to disable TCP mode")
            }

        delay(RESTART_SETTLE_MS)
        if (isReachable(tcpEndpoint.host, tcpEndpoint.port)) {
            return@withContext Result(false, "TCP endpoint is still reachable; setting preserved")
        }

        ShizukuSettings.setAdbTcpEnabled(false)
        Result(true, "ADB TCP disabled")
    }

    private fun execute(endpoint: AdbEndpoint, command: String) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        AdbClient(endpoint.host, endpoint.port, key).use { client ->
            client.connect()
            client.shellCommand(command, null)
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
