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

internal data class AdbEndpoint(
    val host: String,
    val port: Int,
    val requireTls: Boolean = true
)

@RequiresApi(Build.VERSION_CODES.R)
class AdbMdns(
    context: Context,
    private val serviceType: String,
    private val observer: Observer<Int>
) {

    @Volatile
    internal var resolvedEndpoint: AdbEndpoint? = null
        private set

    private var registered = false
    private var running = false
    private var sessionGeneration = 0L
    private var activeListener: DiscoveryListener? = null
    private var serviceName: String? = null
    private var resolveInFlight = false
    private val pendingResolutions = ArrayDeque<NsdServiceInfo>()
    private var restartScheduled = false
    private var restartAttempts = 0
    private val handler = Handler(Looper.getMainLooper())
    private val nsdManager: NsdManager = context.getSystemService(NsdManager::class.java)

    @Synchronized
    fun start() {
        if (running) return
        sessionGeneration++
        resolvedEndpoint = null
        serviceName = null
        resolveInFlight = false
        pendingResolutions.clear()
        running = true
        restartScheduled = false
        restartAttempts = 0
        discover(sessionGeneration)
    }

    @Synchronized
    fun stop() {
        if (!running && !registered) return
        running = false
        sessionGeneration++
        resolvedEndpoint = null
        serviceName = null
        resolveInFlight = false
        pendingResolutions.clear()
        restartScheduled = false
        restartAttempts = 0
        handler.removeCallbacksAndMessages(null)
        val listenerToStop = activeListener
        activeListener = null
        if (registered && listenerToStop != null) {
            registered = false
            runCatching { nsdManager.stopServiceDiscovery(listenerToStop) }
                .onFailure { error -> Log.v(TAG, "stopServiceDiscovery failed", error) }
        }
    }

    @Synchronized
    private fun discover(generation: Long) {
        if (!running || registered || generation != sessionGeneration) return
        val listener = DiscoveryListener(this, generation)
        activeListener = listener
        runCatching {
            nsdManager.discoverServices(serviceType, NsdManager.PROTOCOL_DNS_SD, listener)
        }.onFailure { error ->
            if (activeListener === listener) activeListener = null
            Log.w(TAG, "discoverServices threw for $serviceType; scheduling retry", error)
            scheduleRestart(generation, "exception")
        }
    }

    @Synchronized
    private fun scheduleRestart(generation: Long, reason: String) {
        if (!running || restartScheduled || generation != sessionGeneration) return

        val delayMs = restartDelayMs(restartAttempts)
        restartAttempts++
        restartScheduled = true
        Log.w(TAG, "Scheduling mDNS restart for $serviceType in ${delayMs}ms ($reason)")

        handler.postDelayed({
            synchronized(this@AdbMdns) {
                if (generation != sessionGeneration) return@postDelayed
                restartScheduled = false
                if (running && !registered) discover(generation)
            }
        }, delayMs)
    }

    @Synchronized
    private fun onDiscoveryStart(generation: Long, listener: DiscoveryListener) {
        if (!running || generation != sessionGeneration) {
            runCatching { nsdManager.stopServiceDiscovery(listener) }
            return
        }
        registered = true
        restartScheduled = false
        restartAttempts = 0
    }

    @Synchronized
    private fun onDiscoveryStop(generation: Long, listener: DiscoveryListener) {
        if (activeListener === listener) {
            registered = false
            activeListener = null
        }
        if (running && generation == sessionGeneration) {
            scheduleRestart(generation, "discovery stopped unexpectedly")
        }
    }

    @Synchronized
    private fun onStartDiscoveryFailed(generation: Long, listener: DiscoveryListener, errorCode: Int) {
        if (activeListener === listener) {
            registered = false
            activeListener = null
        }
        if (running && generation == sessionGeneration) {
            scheduleRestart(generation, "start failed: $errorCode")
        }
    }

    @Synchronized
    private fun onServiceFound(generation: Long, info: NsdServiceInfo) {
        if (!running || generation != sessionGeneration) return
        if (pendingResolutions.size < MAX_PENDING_RESOLUTIONS) {
            pendingResolutions.addLast(info)
        }
        drainNextResolutionLocked(generation)
    }

    @Synchronized
    private fun drainNextResolutionLocked(generation: Long) {
        if (!running || generation != sessionGeneration || resolveInFlight) return
        while (pendingResolutions.isNotEmpty()) {
            val next = pendingResolutions.removeFirst()
            resolveInFlight = true
            val started = runCatching {
                @Suppress("DEPRECATION")
                nsdManager.resolveService(next, ResolveListener(this, generation))
            }.onFailure {
                resolveInFlight = false
                Log.w(TAG, "No se pudo resolver el servicio ADB", it)
            }.isSuccess
            if (started) return
        }
    }

    @Synchronized
    private fun onResolveFailed(generation: Long) {
        if (generation != sessionGeneration) return
        resolveInFlight = false
        drainNextResolutionLocked(generation)
    }

    private fun onServiceLost(generation: Long, info: NsdServiceInfo) {
        val shouldNotify = synchronized(this) {
            if (!running || generation != sessionGeneration) return
            if (info.serviceName == serviceName) {
                resolvedEndpoint = null
                serviceName = null
                true
            } else {
                false
            }
        }
        if (shouldNotify) {
            observer.onChanged(-1)
        }
    }

    private fun onServiceResolved(generation: Long, resolvedService: NsdServiceInfo) {
        val candidates = collectCandidateHosts(resolvedService)
        val port = resolvedService.port
        val localHosts = AdbTransportResolver.currentLocalHosts()
        val resolvedPort = synchronized(this) {
            if (generation == sessionGeneration) {
                resolveInFlight = false
            }
            if (!running || generation != sessionGeneration) return
            val selectedHost = AdbEndpointPolicy.selectLocalHost(candidates, port, localHosts)
            if (selectedHost == null) {
                drainNextResolutionLocked(generation)
                return
            }
            serviceName = resolvedService.serviceName
            resolvedEndpoint = AdbEndpoint(selectedHost, port, requireTls = true)
            drainNextResolutionLocked(generation)
            port
        }
        observer.onChanged(resolvedPort)
    }

    private fun collectCandidateHosts(resolvedService: NsdServiceInfo): List<String> {
        val result = ArrayList<String>(4)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
            resolvedService.hostAddresses.forEach { addr ->
                addr.hostAddress?.let { result.add(it) }
            }
        }
        @Suppress("DEPRECATION")
        resolvedService.host?.hostAddress?.let { legacyHost ->
            if (legacyHost !in result) result.add(legacyHost)
        }
        return result
    }

    internal class DiscoveryListener(
        private val adbMdns: AdbMdns,
        private val generation: Long
    ) : NsdManager.DiscoveryListener {
        override fun onDiscoveryStarted(serviceType: String) {
            Log.v(TAG, "onDiscoveryStarted: $serviceType")
            adbMdns.onDiscoveryStart(generation, this)
        }

        override fun onStartDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStartDiscoveryFailed: $serviceType, $errorCode")
            adbMdns.onStartDiscoveryFailed(generation, this, errorCode)
        }

        override fun onDiscoveryStopped(serviceType: String) {
            Log.v(TAG, "onDiscoveryStopped: $serviceType")
            adbMdns.onDiscoveryStop(generation, this)
        }

        override fun onStopDiscoveryFailed(serviceType: String, errorCode: Int) {
            Log.v(TAG, "onStopDiscoveryFailed: $serviceType, $errorCode")
        }

        override fun onServiceFound(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceFound: ${serviceInfo.serviceName}")
            adbMdns.onServiceFound(generation, serviceInfo)
        }

        override fun onServiceLost(serviceInfo: NsdServiceInfo) {
            Log.v(TAG, "onServiceLost: ${serviceInfo.serviceName}")
            adbMdns.onServiceLost(generation, serviceInfo)
        }
    }

    internal class ResolveListener(
        private val adbMdns: AdbMdns,
        private val generation: Long
    ) : NsdManager.ResolveListener {
        override fun onResolveFailed(nsdServiceInfo: NsdServiceInfo, errorCode: Int) {
            Log.v(TAG, "onResolveFailed: ${nsdServiceInfo.serviceName}, $errorCode")
            adbMdns.onResolveFailed(generation)
        }

        override fun onServiceResolved(nsdServiceInfo: NsdServiceInfo) {
            adbMdns.onServiceResolved(generation, nsdServiceInfo)
        }
    }

    companion object {
        const val TLS_CONNECT = "_adb-tls-connect._tcp"
        const val TLS_PAIRING = "_adb-tls-pairing._tcp"
        const val TAG = "AdbMdns"

        private const val MIN_RESTART_DELAY_MS = 2_000L
        private const val MAX_RESTART_DELAY_MS = 30_000L
        private const val MAX_PENDING_RESOLUTIONS = 16

        internal fun restartDelayMs(attempt: Int): Long {
            val shift = attempt.coerceIn(0, 4)
            return (MIN_RESTART_DELAY_MS shl shift).coerceAtMost(MAX_RESTART_DELAY_MS)
        }
    }
}
