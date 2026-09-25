package moe.shizuku.manager.persistence

/**
 * Decides which ADB endpoints survive a default-network change.
 *
 * Wireless debugging on 127.0.0.1 does not depend on Wi-Fi or mobile data.
 * A cached LAN address usually dies when that network changes, and must not
 * hide the loopback fallback.
 */
object NetworkChangePolicy {

    fun isLoopbackHost(host: String?): Boolean {
        if (host.isNullOrBlank()) return false
        val normalized = host.trim()
            .lowercase()
            .removePrefix("[")
            .removeSuffix("]")
            .substringBefore('%')
        if (normalized == "localhost" || normalized == "::1" || normalized == "0:0:0:0:0:0:0:1") {
            return true
        }
        if (!normalized.startsWith("127.")) return false
        val parts = normalized.split('.')
        return parts.size == 4 && parts.all { part -> part.toIntOrNull() in 0..255 }
    }

    fun hostOf(endpoint: String): String {
        val trimmed = endpoint.trim()
        if (trimmed.startsWith("[")) {
            val end = trimmed.indexOf(']')
            if (end > 1) return trimmed.substring(1, end)
        }
        val host = trimmed.substringBeforeLast(':')
        return host.ifBlank { trimmed }
    }

    /** Cached non-loopback mDNS/TCP entries are dropped when the default network changes. */
    fun shouldDropCachedEndpoint(host: String?): Boolean = !isLoopbackHost(host)

    /**
     * Scan loopback wireless debugging when no reachable loopback transport is
     * already known. After a network change, a reachable LAN endpoint is not
     * enough to skip that scan.
     */
    fun shouldDiscoverDynamicLocal(
        networkChanged: Boolean,
        persistentReachable: Boolean,
        persistentHost: String?,
        mdnsReachable: Boolean,
        mdnsHost: String?,
        systemReachable: Boolean,
        systemHost: String?
    ): Boolean {
        val loopbackReady =
            (persistentReachable && isLoopbackHost(persistentHost)) ||
                (mdnsReachable && isLoopbackHost(mdnsHost)) ||
                (systemReachable && isLoopbackHost(systemHost))
        if (loopbackReady) return false
        if (networkChanged) return true
        return !persistentReachable && !mdnsReachable && !systemReachable
    }
}
