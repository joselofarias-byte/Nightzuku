package moe.shizuku.manager.adb

import android.content.Context
import android.net.nsd.NsdManager
import android.net.nsd.NsdServiceInfo
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.util.Log
import androidx.annotation.RequiresApi
import androidx.lifecycle.Observer
import java.net.NetworkInterface
import java.util.concurrent.ConcurrentHashMap

internal data class AdbEndpoint(
    val host: String,
    val port: Int
)

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>
) {

    internal var resolvedEndpoint: AdbEndpoint? = null
        private set

    private var registered = false
    private var running = false
    private var serviceName: String? = null
    private var restartScheduled = false
    private var restartAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val listener = DiscoveryListener(this)
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    fun start() {
        if (running) return
        resolvedEndpoint = null
        running = true
        restartAttempts = 0
        discover()
    }

    fun stop() {
        if (!running) return
        running = false
        resolvedEndpoint = null
        restartScheduled = false
        restartAttempts = 0
        handler.removeCallbacksAndMessages(null)
        if (registered) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
                .onFailure { error -> Log.v(TAG, "stopServiceDiscovery failed", error) }
        }
    }

    private fun discover() {
        if (!running || registered) return
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            Log.w(TAG, "discoverServices threw for $serviceType; scheduling retry", error)
            scheduleRestart("exception")
        }
    }

    private fun scheduleRestart(reason: String) {
        if (!running || restartScheduled) return

        val delayMs = restartDelayMs(restartAttempts)
        restartAttempts++
        restartScheduled = true
        Log.w(TAG, "Scheduling mDNS restart for $serviceType in ${delayMs}ms ($reason)")

        handler.postDelayed({
            restartScheduled = false
            if (running && !registered) discover()
        }, delayMs)
    }

    private fun onDiscoveryStart() {
        registered = true
        if (!running) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            return
        }
        restartScheduled = false
        restartAttempts = 0
    }

    private fun onDiscoveryStop() {
        registered = false
        if (running) scheduleRestart("discovery stopped unexpectedly")
    }

    private fun onStartDiscoveryFailed(errorCode: Int) {
        registered = false
        scheduleRestart("start failed: $errorCode")
    }

    private fun onServiceFound(info: NsdServiceInfo) {
        if (!running) return
        runCatching { nsdManager.resolveService(info, ResolveListener(this)) }
            .onFailure { Log.w(TAG, "No se pudo resolver el servicio ADB", it) }
    }

    private fun onServiceLost(info: NsdServiceInfo) {
        if (info.serviceName == serviceName) {
            resolvedEndpoint = null
            endpoints.remove(serviceType)
            observer.onChanged(-1)
        }
    }

    private fun onServiceResolved(resolvedService: NsdServiceInfo) {
        val host = resolvedService.host?.hostAddress ?: return
        val port = resolvedService.port
        val localHosts = runCatching {
            NetworkInterface.getNetworkInterfaces().asSequence()
                .flatMap { it.inetAddresses.asSequence() }
                .mapNotNull { it.hostAddress }.toSet()
        }.getOrDefault(emptySet())
        if (running && AdbEndpointPolicy.isLocal(host, port, localHosts)) {
            serviceName = resolvedService.serviceName
            val endpoint = AdbEndpoint(host, port)
            resolvedEndpoint = endpoint
            endpoints[serviceType] = endpoint
            observer.onChanged(port)
        }
    }

    internal class DiscoveryListener(private val adbMdns: AdbMdns) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart()
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onStartDiscoveryFailed(errorCode)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop()
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(serviceInfo)
        }
    }

    internal class ResolveListener(private val adbMdns: AdbMdns) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            Log.v(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, $errorCode")
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"

        private const val MIN_RESTART_DELAY_MS = 2_000L
        private const val MAX_RESTART_DELAY_MS = 30_000L
        private val endpoints = ConcurrentHashMap<String, AdbEndpoint>()

        internal fun restartDelayMs(attempt: Int): Long {
            val shift = attempt.coerceIn(0, 4)
            return (MIN_RESTART_DELAY_MS shl shift).coerceAtMost(MAX_RESTART_DELAY_MS)
        }

        internal fun getDiscoveredEndpoint(serviceType: String): AdbEndpoint? = endpoints[serviceType]

        internal fun getResolvedEndpoint(serviceType: String): AdbEndpoint? {
            if (serviceType == TLS_CONNECT) {
                AdbTransportResolver.persistentTcpEndpoint()?.let { return it }
            }
            return getDiscoveredEndpoint(serviceType)
        }
    }
}
