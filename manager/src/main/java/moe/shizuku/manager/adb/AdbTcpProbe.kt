package moe.shizuku.manager.adb

import java.net.InetSocketAddress
import java.net.Socket

/** Socket-only reachability probe. This is not ADB authentication. */
object AdbTcpProbe {

    fun isReachable(host: String, port: Int, timeoutMs: Int = CONNECT_TIMEOUT_MS): Boolean {
        if (host.isBlank() || port !in 1..65535) return false
        return runCatching {
            Socket().use { socket ->
                socket.connect(InetSocketAddress(host, port), timeoutMs)
            }
            true
        }.getOrDefault(false)
    }

    private const val CONNECT_TIMEOUT_MS = 1_000
}
