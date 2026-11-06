package com.joselofarias.oppoassist.service

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Intent
import android.content.pm.ServiceInfo
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.os.Build
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import com.joselofarias.oppoassist.OppoAssistApp
import com.joselofarias.oppoassist.adb.AdbWatch
import com.joselofarias.oppoassist.R
import com.joselofarias.oppoassist.core.AgentInfo
import com.joselofarias.oppoassist.core.BindPolicy
import com.joselofarias.oppoassist.core.HttpAgentServer
import com.joselofarias.oppoassist.core.LocalNic
import com.joselofarias.oppoassist.core.TailscaleBind
import com.joselofarias.oppoassist.rpc.AndroidRpcHandler
import com.joselofarias.oppoassist.sms.InboxWatcher
import com.joselofarias.oppoassist.ui.MainActivity
import java.net.NetworkInterface
import java.util.Collections

class AssistService : Service() {
    private val handler = Handler(Looper.getMainLooper())
    private var server: HttpAgentServer? = null
    private var bound: String? = null
    private var callback: ConnectivityManager.NetworkCallback? = null
    private var ticks = 0
    private val tick = object : Runnable {
        override fun run() {
            ensureServer()
            ticks++
            if (ticks % 2 == 0) InboxWatcher.poll(OppoAssistApp.from(this@AssistService))
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
        val text = "Iniciando asistencia"
        startInForeground(text)
        AdbWatch.start(this)
        handler.post(tick)
        registerNetwork()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        startInForeground(OppoAssistApp.from(this).prefs.getString(OppoAssistApp.KEY_STATUS, null) ?: "Asistencia activa")
        return START_STICKY
    }

    override fun onDestroy() {
        handler.removeCallbacksAndMessages(null)
        AdbWatch.stop()
        server?.stop()
        callback?.let { cb ->
            runCatching {
                getSystemService(ConnectivityManager::class.java).unregisterNetworkCallback(cb)
            }
        }
        super.onDestroy()
    }

    private fun registerNetwork() {
        val manager = getSystemService(ConnectivityManager::class.java) ?: return
        val request = NetworkRequest.Builder()
            .addTransportType(NetworkCapabilities.TRANSPORT_VPN)
            .build()
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                handler.post { ensureServer() }
            }

            override fun onLost(network: Network) {
                handler.post { ensureServer() }
            }
        }
        callback = cb
        runCatching { manager.registerNetworkCallback(request, cb) }
    }

    private fun ensureServer() {
        val app = OppoAssistApp.from(this)
        val address = runCatching {
            val nics = Collections.list(NetworkInterface.getNetworkInterfaces()).map { nic ->
                LocalNic(nic.name, nic.isUp, Collections.list(nic.inetAddresses))
            }
            TailscaleBind.choose(nics)
        }.getOrNull()
        if (address == null) {
            server?.stop()
            server = null
            bound = null
            publish("Tailscale no está conectado. El agente no escucha.")
            return
        }
        val host = address.hostAddress ?: return
        if (server != null && bound == host) return
        server?.stop()
        val http = HttpAgentServer(
            address = address,
            port = AgentInfo.PORT,
            token = app.token,
            policy = BindPolicy.production(),
            handler = AndroidRpcHandler(app),
        )
        try {
            http.start()
            server = http
            bound = host
            publish("Escuchando solo en Tailscale $host:${AgentInfo.PORT}")
        } catch (_: Exception) {
            server = null
            bound = null
            publish("No se pudo abrir el puerto en la IP de Tailscale.")
        }
    }

    private fun publish(text: String) {
        OppoAssistApp.from(this).prefs.edit().putString(OppoAssistApp.KEY_STATUS, text).apply()
        val manager = getSystemService(NotificationManager::class.java)
        manager.notify(NOTIFICATION_ID, notification(text))
    }

    private fun startInForeground(text: String) {
        val type = if (Build.VERSION.SDK_INT >= 34) {
            ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE
        } else {
            0
        }
        ServiceCompat.startForeground(this, NOTIFICATION_ID, notification(text), type)
    }

    private fun notification(text: String) = NotificationCompat.Builder(this, CHANNEL)
        .setSmallIcon(R.drawable.ic_notify)
        .setContentTitle(getString(R.string.notification_title))
        .setContentText(text)
        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
        .setOngoing(true)
        .setOnlyAlertOnce(true)
        .setContentIntent(
            PendingIntent.getActivity(
                this,
                1,
                Intent(this, MainActivity::class.java),
                PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT,
            ),
        )
        .build()

    private fun createChannel() {
        val manager = getSystemService(NotificationManager::class.java)
        val channel = NotificationChannel(
            CHANNEL,
            getString(R.string.channel_status),
            NotificationManager.IMPORTANCE_LOW,
        )
        channel.setShowBadge(false)
        manager.createNotificationChannel(channel)
    }

    companion object {
        private const val CHANNEL = "assist"
        const val NOTIFICATION_ID = 7
    }
}
