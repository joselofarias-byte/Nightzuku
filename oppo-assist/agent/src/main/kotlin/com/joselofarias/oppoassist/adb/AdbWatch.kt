package com.joselofarias.oppoassist.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.provider.Settings
import java.util.concurrent.Executors

/**
 * Observa el servicio que Nightzuku también busca: `_adb-tls-connect._tcp`.
 * No escribe adb_wifi_enabled. El puerto puede cambiar; la IP anunciada se filtra
 * antes de salir por Tailscale.
 */
object AdbWatch {
    data class Seen(val port: Int?, val host: String?)

    @Volatile private var port: Int? = null
    @Volatile private var host: String? = null
    private var manager: NsdManager? = null
    private var listener: NsdManager.DiscoveryListener? = null
    private val executor = Executors.newSingleThreadExecutor()

    fun current(): Seen = Seen(port, host)

    fun wifiEnabled(context: Context): Boolean? = runCatching {
        Settings.Global.getInt(context.contentResolver, "adb_wifi_enabled") == 1
    }.getOrNull()

    fun start(context: Context) {
        if (listener != null) return
        val nsd = context.getSystemService(NsdManager::class.java) ?: return
        manager = nsd
        val discovery = object : NsdManager.DiscoveryListener {
            override fun onDiscoveryStarted(serviceType: String) = Unit
            override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) = Unit
            override fun onDiscoveryStopped(serviceType: String) = Unit
            override fun onServiceLost(serviceInfo: NsdServiceInfo) {
                port = null
                host = null
            }

            override fun onServiceFound(serviceInfo: NsdServiceInfo) {
                resolve(nsd, serviceInfo)
            }
        }
        listener = discovery
        runCatching { nsd.discoverServices(SERVICE, NsdManager.PROTOCOL_DNS_SD, discovery) }
    }

    fun stop() {
        val nsd = manager
        val discovery = listener
        listener = null
        if (nsd != null && discovery != null) {
            runCatching { nsd.stopServiceDiscovery(discovery) }
        }
        manager = null
    }

    private fun resolve(nsd: NsdManager, info: NsdServiceInfo) {
        if (Build.VERSION.SDK_INT >= 34) {
            nsd.registerServiceInfoCallback(
                info,
                executor,
                object : NsdManager.ServiceInfoCallback {
                    override fun onServiceInfoCallbackRegistrationFailed(errorCode: Int) = Unit
                    override fun onServiceUpdated(serviceInfo: NsdServiceInfo) = remember(serviceInfo)
                    override fun onServiceLost() = Unit
                    override fun onServiceInfoCallbackUnregistered() = Unit
                },
            )
            return
        }
        @Suppress("DEPRECATION")
        nsd.resolveService(
            info,
            object : NsdManager.ResolveListener {
                override fun onResolveFailed(serviceInfo: NsdServiceInfo, errorCode: Int) = Unit
                override fun onServiceResolved(serviceInfo: NsdServiceInfo) = remember(serviceInfo)
            },
        )
    }

    private fun remember(info: NsdServiceInfo) {
        val seenPort = info.port
        if (seenPort !in 1..65535) return
        port = seenPort
        host = advertisedHost(info)
    }

    private fun advertisedHost(info: NsdServiceInfo): String? {
        if (Build.VERSION.SDK_INT >= 34) {
            return info.hostAddresses.firstOrNull()?.hostAddress
        }
        @Suppress("DEPRECATION")
        return info.host?.hostAddress
    }

    private const val SERVICE = "_adb-tls-connect._tcp"
}
