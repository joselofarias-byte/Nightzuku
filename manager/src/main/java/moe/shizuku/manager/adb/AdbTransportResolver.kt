package moe.shizuku.manager.adb

import android.content.Context
import android.os.Build
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
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


    // El puerto TLS cambia: cada operación tiene su propia sesión NSD y su limpieza.
    suspend fun discover(context: Context): AdbEndpoint? {
        persistentTcpEndpoint()?.let { return it }
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return null
        return discoverWireless(context)
    }

    suspend fun discoverWireless(context: Context): AdbEndpoint? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        var discovery: AdbMdns? = null
        try {
            withTimeoutOrNull(8_000L) {
                suspendCancellableCoroutine { continuation ->
                    val session = AdbMdns(context.applicationContext, AdbMdns.TLS_CONNECT) { port ->
                        val endpoint = discovery?.resolvedEndpoint
                        if (port > 0 && endpoint != null && continuation.isActive) {
                            continuation.resume(endpoint)
                        }
                    }
                    discovery = session
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

        return AdbEndpoint(host, port)
    }

}
