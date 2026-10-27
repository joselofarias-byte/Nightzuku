package moe.shizuku.manager.adb

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import moe.shizuku.manager.ShizukuSettings
import java.net.InetSocketAddress
import java.net.Socket

/**
 * Bounded fallback for Android's rotating local Wireless Debugging port.
 *
 * Never trusts a merely-open socket: the candidate must complete an authenticated
 * ADB handshake using Nightzuku's already-paired key.
 *
 * Used only after the inexpensive configured/mDNS paths have failed.
 */
internal object AdbLocalPortDiscovery {
    private const val HOST = "127.0.0.1"
    private const val MIN_PORT = 30000
    private const val MAX_PORT = 60000
    private const val CONNECT_TIMEOUT_MS = 65
    private const val MAX_PARALLEL = 96
    private const val BATCH_SIZE = 512

    suspend fun find(): AdbEndpoint? = withContext(Dispatchers.IO) {
        val key = runCatching {
            AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        }.getOrNull() ?: return@withContext null

        val gate = Semaphore(MAX_PARALLEL)
        // Probe each batch concurrently but authenticate sequentially to avoid
        // stressing adbd and to stop as soon as a valid transport appears.
        for (start in MIN_PORT..MAX_PORT step BATCH_SIZE) {
            val end = minOf(start + BATCH_SIZE - 1, MAX_PORT)
            val candidates = coroutineScope {
                (start..end).map { port ->
                    async {
                        gate.withPermit {
                            val open = runCatching {
                                Socket().use { it.connect(InetSocketAddress(HOST, port), CONNECT_TIMEOUT_MS) }
                                true
                            }.getOrDefault(false)
                            if (open) port else null
                        }
                    }
                }.awaitAll().filterNotNull()
            }
            for (port in candidates) {
                val valid = runCatching {
                    AdbClient(HOST, port, key).use { it.connect() }
                    true
                }.getOrDefault(false)
                if (valid) return@withContext AdbEndpoint(HOST, port)
            }
        }
        null
    }
}
