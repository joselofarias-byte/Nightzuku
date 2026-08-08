package moe.shizuku.manager.shizuku

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.SystemClock
import androidx.lifecycle.Observer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.adb.AdbMdns
import moe.shizuku.manager.starter.StarterActivity
import moe.shizuku.manager.utils.EnvironmentUtils
import rikka.shizuku.Shizuku

/** Process-level watchdog that keeps the service aligned with the persisted desired state. */
object NightDogRecovery {

    private const val PREFS_NAME = "nightdog_recovery"
    private const val KEY_DESIRED_RUNNING = "desired_running"

    private const val POLL_INTERVAL_MS = 4_000L
    private const val RECOVERY_SETTLE_MS = 1_500L
    private const val MIN_RETRY_MS = 8_000L
    private const val MAX_RETRY_MS = 60_000L

    enum class Stage {
        IDLE,
        CHECKING_BINDER,
        DISCOVERING_ADB,
        WAITING_FOR_ADB,
        STARTING_SERVICE,
        RUNNING,
        MANUALLY_STOPPED,
        ERROR
    }

    data class Snapshot(
        val stage: Stage = Stage.IDLE,
        val desiredRunning: Boolean = true,
        val binderAlive: Boolean = false,
        val transport: String? = null,
        val endpoint: String? = null,
        val failedAttempts: Int = 0,
        val lastResult: String = "Sin comprobaciones todavía",
        val lastAttemptElapsedRealtime: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var applicationContext: Context? = null
    @Volatile private var pollingJob: Job? = null
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var failedAttempts = 0
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var adbMdns: AdbMdns? = null

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val mdnsObserver = Observer<Int> { port ->
        if (port != null && port > 0) {
            publish(Stage.DISCOVERING_ADB, "mDNS encontró un endpoint ADB; preparando inicio")
            requestRecovery()
        }
    }

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        applicationContext?.let { context ->
            preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
        failedAttempts = 0
        lastAttemptAt = 0L
        recoveryJob?.cancel()
        recoveryJob = null
        publishRunning("Binder recibido; servicio activo")
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        publish(Stage.CHECKING_BINDER, "Binder perdido; iniciando recuperación", binderAlive = false)
        requestRecovery()
    }

    @Synchronized
    fun start(context: Context) {
        applicationContext = context.applicationContext
        ensureDesiredStateInitialized(context)
        if (pollingJob?.isActive == true) return

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R && adbMdns == null) {
            adbMdns = AdbMdns(applicationContext!!, AdbMdns.TLS_CONNECT, mdnsObserver).also { it.start() }
        }

        pollingJob = scope.launch {
            while (isActive) {
                val alive = Shizuku.pingBinder()
                if (alive) {
                    failedAttempts = 0
                    lastAttemptAt = 0L
                    publishRunning("Binder responde correctamente")
                } else if (isDesiredRunning()) {
                    publish(Stage.CHECKING_BINDER, "Binder no responde; buscando transporte ADB", binderAlive = false)
                    requestRecovery()
                } else {
                    publish(Stage.MANUALLY_STOPPED, "Servicio detenido manualmente", binderAlive = false)
                }
                delay(POLL_INTERVAL_MS)
            }
        }

        if (isDesiredRunning(context) && !Shizuku.pingBinder()) {
            publish(Stage.CHECKING_BINDER, "Inicio de la app: servicio deseado activo; comprobando ADB", binderAlive = false)
            requestRecovery()
        }
    }

    @Synchronized
    fun requestManualStart(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        failedAttempts = 0
        lastAttemptAt = 0L
        publish(Stage.CHECKING_BINDER, "Inicio solicitado; comprobando Binder y ADB")
        if (!Shizuku.pingBinder()) requestRecovery()
    }

    @Synchronized
    fun prepareForManualStop(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, false).apply()
        recoveryJob?.cancel()
        recoveryJob = null
        failedAttempts = 0
        lastAttemptAt = 0L
        publish(Stage.MANUALLY_STOPPED, "Detenido manualmente; recuperación deshabilitada", binderAlive = false)
    }

    fun prepareForManualStop() {
        val context = applicationContext ?: return
        prepareForManualStop(context)
    }

    fun isDesiredRunning(context: Context? = applicationContext): Boolean {
        val resolvedContext = context ?: return true
        return preferences(resolvedContext).getBoolean(KEY_DESIRED_RUNNING, true)
    }

    @Synchronized
    fun stop() {
        Shizuku.removeBinderReceivedListener(binderReceivedListener)
        Shizuku.removeBinderDeadListener(binderDeadListener)
        pollingJob?.cancel()
        pollingJob = null
        recoveryJob?.cancel()
        recoveryJob = null
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) adbMdns?.stop()
        adbMdns = null
        applicationContext = null
        failedAttempts = 0
        lastAttemptAt = 0L
        _snapshot.value = Snapshot(stage = Stage.IDLE, lastResult = "Watchdog detenido")
    }

    private fun ensureDesiredStateInitialized(context: Context) {
        val prefs = preferences(context)
        if (!prefs.contains(KEY_DESIRED_RUNNING)) {
            prefs.edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        }
    }

    private fun preferences(context: Context) =
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)

    private fun retryDelayMs(): Long {
        if (failedAttempts <= 0) return 0L
        val shift = (failedAttempts - 1).coerceAtMost(3)
        return (MIN_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    private fun resolveEndpoint(): Triple<String, Int, String>? {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            AdbMdns.getResolvedEndpoint(AdbMdns.TLS_CONNECT)?.let { endpoint ->
                val transport = if (endpoint.host == "127.0.0.1" || endpoint.host == "localhost") {
                    "TCP persistente/local"
                } else {
                    "ADB mDNS/TLS"
                }
                return Triple(endpoint.host, endpoint.port, transport)
            }
        }

        val port = EnvironmentUtils.getAdbTcpPort()
        if (port > 0) return Triple("127.0.0.1", port, "ADB local")
        return null
    }

    private fun requestRecovery() {
        if (Shizuku.pingBinder()) {
            publishRunning("Binder ya está activo")
            return
        }
        if (!isDesiredRunning()) {
            publish(Stage.MANUALLY_STOPPED, "Recuperación omitida: detención manual")
            return
        }
        if (recoveryJob?.isActive == true) return

        val now = SystemClock.elapsedRealtime()
        val retryDelay = retryDelayMs()
        if (lastAttemptAt != 0L && now - lastAttemptAt < retryDelay) {
            publish(Stage.WAITING_FOR_ADB, "Esperando ${((retryDelay - (now - lastAttemptAt)) / 1000).coerceAtLeast(1)} s antes del próximo intento")
            return
        }

        recoveryJob = scope.launch {
            delay(RECOVERY_SETTLE_MS)
            if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch

            val context = applicationContext ?: return@launch
            publish(Stage.DISCOVERING_ADB, "Binder ausente; resolviendo TCP persistente, mDNS/TLS y ADB local")

            val endpoint = resolveEndpoint()
            failedAttempts++
            lastAttemptAt = SystemClock.elapsedRealtime()

            if (endpoint == null) {
                publish(
                    Stage.WAITING_FOR_ADB,
                    "Sin endpoint ADB utilizable. Se seguirá buscando automáticamente",
                    transport = "ninguno",
                    endpoint = null
                )
                return@launch
            }

            val (host, port, transport) = endpoint
            publish(
                Stage.STARTING_SERVICE,
                "Endpoint disponible; iniciando servicio",
                transport = transport,
                endpoint = "$host:$port"
            )

            val intent = Intent(context, StarterActivity::class.java).apply {
                putExtra(StarterActivity.EXTRA_IS_ROOT, false)
                putExtra(StarterActivity.EXTRA_HOST, host)
                putExtra(StarterActivity.EXTRA_PORT, port)
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_NO_ANIMATION)
            }

            runCatching {
                context.startActivity(intent)
            }.onFailure { error ->
                publish(
                    Stage.ERROR,
                    "No se pudo abrir el iniciador: ${error.javaClass.simpleName}",
                    transport = transport,
                    endpoint = "$host:$port"
                )
            }
        }
    }

    private fun publishRunning(result: String) {
        publish(
            Stage.RUNNING,
            result,
            binderAlive = true,
            transport = "Binder activo",
            endpoint = "no requerido"
        )
    }

    private fun publish(
        stage: Stage,
        result: String,
        binderAlive: Boolean = Shizuku.pingBinder(),
        transport: String? = _snapshot.value.transport,
        endpoint: String? = _snapshot.value.endpoint
    ) {
        _snapshot.value = Snapshot(
            stage = stage,
            desiredRunning = isDesiredRunning(),
            binderAlive = binderAlive,
            transport = transport,
            endpoint = endpoint,
            failedAttempts = failedAttempts,
            lastResult = result,
            lastAttemptElapsedRealtime = lastAttemptAt
        )
    }
}
