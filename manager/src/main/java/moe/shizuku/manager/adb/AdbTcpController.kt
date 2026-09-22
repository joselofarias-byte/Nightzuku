package moe.shizuku.manager.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.persistence.TcpHealth
import moe.shizuku.manager.persistence.TcpHealthClassifier

/**
 * Changes adbd transport mode through the daemon's official restart services.
 * The Nightzuku preference is committed only after the requested endpoint is verified.
 */
object AdbTcpController {

    data class Result(
        val success: Boolean,
        val message: String,
        val authenticated: Boolean = false
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
        if (AdbTcpProbe.isReachable(tcpEndpoint.host, tcpEndpoint.port)) {
            return@withContext Result(false, "TCP endpoint is still reachable; setting was preserved")
        }

        ShizukuSettings.setAdbTcpEnabled(false)
        Result(true, "ADB TCP disabled")
    }

    suspend fun testStored(): Result = withContext(Dispatchers.IO) {
        val endpoint = AdbTransportResolver.persistentTcpEndpoint()
            ?: return@withContext Result(false, "No stored persistent TCP endpoint")
        test(endpoint.host, endpoint.port)
    }

    suspend fun test(host: String, port: Int): Result = withContext(Dispatchers.IO) {
        val normalizedHost = host.trim()
        if (normalizedHost.isEmpty() || port !in 1..65535) {
            return@withContext Result(false, "Invalid TCP host or port")
        }
        authenticate(normalizedHost, port)
    }

    suspend fun classifyStored(): TcpHealth = withContext(Dispatchers.IO) {
        val enabled = ShizukuSettings.isAdbTcpEnabled()
        val host = ShizukuSettings.getAdbTcpHost().trim()
        val port = ShizukuSettings.getAdbTcpPort()
        if (!enabled) {
            return@withContext TcpHealthClassifier.classify(false, host, port, null, null)
        }
        if (host.isEmpty() || port !in 1..65535) {
            return@withContext TcpHealthClassifier.classify(true, host, port, null, null)
        }
        val reachable = AdbTcpProbe.isReachable(host, port)
        val authenticated = if (reachable) authenticate(host, port).authenticated else false
        TcpHealthClassifier.classify(true, host, port, reachable, authenticated)
    }

    private fun executeService(endpoint: AdbEndpoint, service: String) {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        AdbClient(endpoint.host, endpoint.port, key).use { client ->
            client.connect()
            client.serviceCommand(service)
        }
    }

    private fun authenticate(host: String, port: Int): Result {
        return runCatching {
            val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
            AdbClient(host, port, key).use { client ->
                client.connect()
            }
            Result(true, "Authenticated ADB session at $host:$port", authenticated = true)
        }.getOrElse { error ->
            Result(
                false,
                "ADB authentication failed at $host:$port: ${error.message ?: error.javaClass.simpleName}",
                authenticated = false
            )
        }
    }

    private suspend fun awaitReachable(host: String, port: Int): Boolean {
        repeat(VERIFY_ATTEMPTS) {
            delay(VERIFY_INTERVAL_MS)
            if (AdbTcpProbe.isReachable(host, port)) return true
        }
        return false
    }

    private const val VERIFY_ATTEMPTS = 8
    private const val VERIFY_INTERVAL_MS = 1_000L
    private const val RESTART_SETTLE_MS = 2_000L
}
