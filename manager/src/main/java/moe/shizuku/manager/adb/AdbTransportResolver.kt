package moe.shizuku.manager.adb

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager
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
    fun acceptsAnnouncement(port: Int, endpoint: AdbEndpoint, excluded: AdbEndpoint?): Boolean =
        port in 1..65535 && endpoint.port == port && endpoint != excluded

    fun isLocal(host: String, port: Int, localHosts: Set<String>): Boolean =
        port in 1..65535 && host in localHosts

    /**
     * Selecciona el mejor host local entre los reportados por NSD (Android 14-16 puede
     * devolver múltiples direcciones IPv4/IPv6). Prefiere IPv4 local cuando existe.
     */
    fun selectLocalHost(candidates: List<String>, port: Int, localHosts: Set<String>): String? {
        if (port !in 1..65535 || localHosts.isEmpty()) return null
        val localMatches = candidates.filter { isLocal(it, port, localHosts) }
        if (localMatches.isEmpty()) return null
        return localMatches.firstOrNull { !it.contains(':') } ?: localMatches.first()
    }
}

internal sealed class AdbDiscoveryResult {
    data class Found(val endpoint: AdbEndpoint) : AdbDiscoveryResult()
    object WirelessDebuggingDisabled : AdbDiscoveryResult()
    object Absent : AdbDiscoveryResult()
}

internal sealed class WirelessDebuggingRestoreResult {
    object AlreadyEnabled : WirelessDebuggingRestoreResult()
    object Enabled : WirelessDebuggingRestoreResult()
    object NotPermitted : WirelessDebuggingRestoreResult()
    object DeveloperOptionsDisabled : WirelessDebuggingRestoreResult()
    object WifiRequired : WirelessDebuggingRestoreResult()
    data class SystemRejected(val reason: String) : WirelessDebuggingRestoreResult()
}

/**
 * Redescubre el endpoint ADB local en cada operación mediante una sesión NSD fresca.
 * Conserva el fallback TCP legacy únicamente si fue habilitado explícitamente y su host
 * pertenece a una interfaz local actual.
 */
internal object AdbTransportResolver {

    internal const val DISCOVERY_TIMEOUT_MS = 8_000L
    internal const val ADB_WIFI_ENABLED = "adb_wifi_enabled"

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

    /**
     * Devuelve 1 si Opciones de desarrollador están activas, 0 si no, o -1 si no se pudo leer.
     */
    fun developerOptionsEnabled(context: Context): Int = runCatching {
        Settings.Global.getInt(context.contentResolver, Settings.Global.DEVELOPMENT_SETTINGS_ENABLED, -1)
    }.getOrDefault(-1)

    fun hasWriteSecureSettingsPermission(context: Context): Boolean = runCatching {
        context.checkSelfPermission(Manifest.permission.WRITE_SECURE_SETTINGS) == PackageManager.PERMISSION_GRANTED
    }.getOrDefault(false)

    fun isWifiConnected(context: Context): Boolean = runCatching {
        val cm = context.getSystemService(ConnectivityManager::class.java) ?: return@runCatching false
        val active = cm.activeNetwork ?: return@runCatching false
        val caps = cm.getNetworkCapabilities(active) ?: return@runCatching false
        caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI)
    }.getOrDefault(false)

    fun isIgnoringBatteryOptimizations(context: Context): Boolean = runCatching {
        val pm = context.getSystemService(PowerManager::class.java) ?: return@runCatching false
        pm.isIgnoringBatteryOptimizations(context.packageName)
    }.getOrDefault(false)

    fun currentNetworkFingerprint(context: Context): String {
        val wifi = isWifiConnected(context)
        val hosts = currentLocalHosts()
            .filterNot { it == "127.0.0.1" || it == "::1" || it.startsWith("fe80:") }
            .sorted()
            .joinToString(",")
        return if (wifi) "wifi:$hosts" else "nowifi:$hosts"
    }

    /**
     * Intenta rehabilitar únicamente `adb_wifi_enabled` aprovechando `WRITE_SECURE_SETTINGS`
     * SOLO si ya fue concedido al paquete y las precondiciones del sistema lo permiten.
     * Nunca modifica `adb_enabled` ni reinicia el demonio ADB global.
     */
    fun tryEnableWirelessDebugging(context: Context): WirelessDebuggingRestoreResult {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
            return WirelessDebuggingRestoreResult.NotPermitted
        }
        if (wirelessDebuggingState(context) == 1) {
            return WirelessDebuggingRestoreResult.AlreadyEnabled
        }
        if (!hasWriteSecureSettingsPermission(context)) {
            return WirelessDebuggingRestoreResult.NotPermitted
        }
        if (developerOptionsEnabled(context) == 0) {
            return WirelessDebuggingRestoreResult.DeveloperOptionsDisabled
        }
        if (!isWifiConnected(context)) {
            return WirelessDebuggingRestoreResult.WifiRequired
        }
        return try {
            val wrote = Settings.Global.putInt(context.contentResolver, ADB_WIFI_ENABLED, 1)
            val confirmed = wirelessDebuggingState(context) == 1
            if (wrote && confirmed) {
                WirelessDebuggingRestoreResult.Enabled
            } else {
                WirelessDebuggingRestoreResult.SystemRejected(
                    "Android/MagicOS no mantuvo adb_wifi_enabled activo tras la escritura"
                )
            }
        } catch (error: SecurityException) {
            WirelessDebuggingRestoreResult.SystemRejected(
                error.message ?: "SecurityException al escribir adb_wifi_enabled"
            )
        } catch (error: Throwable) {
            WirelessDebuggingRestoreResult.SystemRejected(
                error.message ?: error.javaClass.simpleName
            )
        }
    }

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
        timeoutMs: Long = DISCOVERY_TIMEOUT_MS,
        excludedEndpoint: AdbEndpoint? = null
    ): AdbEndpoint? = withContext(Dispatchers.Main) {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) return@withContext null
        val appContext = context.applicationContext
        val multicastLock = acquireShortMulticastLock(appContext)
        var discovery: AdbMdns? = null
        try {
            withTimeoutOrNull(timeoutMs) {
                suspendCancellableCoroutine { continuation ->
                    val resumed = AtomicBoolean(false)
                    val session = AdbMdns(appContext, AdbMdns.TLS_CONNECT) { port ->
                        val endpoint = discovery?.resolvedEndpoint
                        if (endpoint != null && AdbEndpointPolicy.acceptsAnnouncement(port, endpoint, excludedEndpoint) &&
                            continuation.isActive && resumed.compareAndSet(false, true)
                        ) {
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
            releaseMulticastLock(multicastLock)
        }
    }

    private fun acquireShortMulticastLock(context: Context): WifiManager.MulticastLock? = runCatching {
        val wifiManager = context.getSystemService(WifiManager::class.java) ?: return@runCatching null
        wifiManager.createMulticastLock("NightzukuAdbMdns").apply {
            setReferenceCounted(false)
            acquire()
        }
    }.getOrNull()

    private fun releaseMulticastLock(lock: WifiManager.MulticastLock?) {
        runCatching {
            if (lock?.isHeld == true) {
                lock.release()
            }
        }
    }

    suspend fun alternateLocalEndpoint(context: Context, previous: AdbEndpoint): AdbEndpoint? {
        if (previous.requireTls) {
            localPersistentTcpEndpoint()?.let { return it }
        }
        if (wirelessDebuggingState(context) == 0) return null

        // El anuncio anterior puede llegar primero tras cambiar de red; seguir escuchando.
        return discoverWireless(context, excludedEndpoint = previous)
    }

    fun canTryAlternate(error: Exception, launchSubmitted: Boolean): Boolean =
        error is AdbShellUnavailableException ||
            (!launchSubmitted && (error is java.net.ConnectException ||
                error is java.net.SocketTimeoutException ||
                error is java.net.NoRouteToHostException || error is AdbOfflineException))

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
