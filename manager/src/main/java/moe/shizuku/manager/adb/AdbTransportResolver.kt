package moe.shizuku.manager.adb

import android.content.Context
import android.os.Build
import android.provider.Settings
import java.net.NetworkInterface
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import moe.shizuku.manager.ShizukuSettings

internal object AdbEndpointPolicy {
    fun isLocal(host: String, port: Int, localHosts: Set<String>): Boolean =
        port in 1..65535 && host in localHosts
}

internal sealed class AdbDiscoveryResult {
    data class Found(val endpoint: AdbEndpoint) : AdbDiscoveryResult()
    object WirelessDebuggingDisabled : AdbDiscoveryResult()
    object Absent : AdbDiscoveryResult()
}

/**
 * Redescubre el endpoint ADB local en cada operación mediante una sesión NSD fresca.
 * Conserva el fallback TCP legacy únicamente si fue habilitado explícitamente y su host
 * pertenece a una interfaz local actual.
 */
internal object AdbTransportResolver {

    internal const val DISCOVERY_TIMEOUT_MS = 8_000L
    private const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

    fun currentLocalHosts(): Set<String> = runCatching {
        NetworkInterface.getNetworkInterfaces()
            ?.asSequence()
            ?.flatMap { it.inetAddresses.asSequence() }
            ?.mapNotNull { it.hostAddress }
            ?.toSet()
            .orEmpty()
    }.getOrDefault(emptySet())

    /**
     * Devuelve 1 si la depuración inalámbrica está activa, 0 si está explícitamente
     * desactivada, o -1 si no se pudo determinar.
     */
    fun wirelessDebuggingState(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, ADB_WIFI_ENABLED, -1)
    }.getOrDefault(-1)

    suspend fun discover(context: Context, timeoutMs: Long = DISCOVERY_TIMEOUT_MS): AdbEndpoint? =
        (discoverDetailed(context, timeoutMs) as? AdbDiscoveryResult.Found)?.endpoint

    suspend fun discoverDetailed(
        context: Context,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): AdbDiscoveryResult {
        val wirelessState = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            wirelessDebuggingState(context)
        } else {
            -1
        }

        if (wirelessState != 0) {
            discoverWireless(context, timeoutMs)?.let { return AdbDiscoveryResult.Found(it) }
        }

        localPersistentTcpEndpoint()?.let { return AdbDiscoveryResult.Found(it) }

        return if (wirelessState == 0) {
            AdbDiscoveryResult.WirelessDebuggingDisabled
        } else {
            AdbDiscoveryResult.Absent
        }
    }

    suspend fun discoverWireless(
        context: Context,
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS
    ): AdbEndpoint? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        var discovery: AdbMdns? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    val session = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT) { port ->
                        val endpoint = discovery?.resolvedEndpoint
                        if (port > 0 && endpoint != null && resumed.compareAndSet(false, true) && continuation.isActive) {
                            continuation.resume(endpoint)
                        }
                    }
                    discovery = session
                    continuation.invokeOnCancellation { session.stop() }
                    session.start()
                }
            }
        } finally {
            discovery?.stop()
        }
    }

    fun persistentTcpEndpoint(): AdbEndpoint? {
        if (!ShizukuSettings.isAdbTcpEnabled()) return null

        val host = ShizukuSettings.getAdbTcpHost().trim()
        val port = ShizukuSettings.getAdbTcpPort()
        if (host.isEmpty() || port !in 1..65535) return null

        return AdbEndpoint(host, port, requireTls = false)
    }

    fun localPersistentTcpEndpoint(localHosts: Set<String> = currentLocalHosts()): AdbEndpoint? {
        val endpoint = persistentTcpEndpoint() ?: return null
        return endpoint.takeIf { AdbEndpointPolicy.isLocal(it.host, it.port, localHosts) }
    }
}
