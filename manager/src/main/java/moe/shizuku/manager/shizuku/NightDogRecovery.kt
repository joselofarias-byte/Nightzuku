package moe.shizuku.manager.shizuku

import android.content.Context
import android.os.SystemClock
import android.os.UserManager
import android.util.Log
import java.io.ByteArrayOutputStream
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbClient
import moe.shizuku.manager.adb.AdbKey
import moe.shizuku.manager.adb.AdbEndpoint
import moe.shizuku.manager.adb.AdbTransportResolver
import moe.shizuku.manager.adb.PreferenceAdbKeyStore
import moe.shizuku.manager.starter.Starter
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
        val lastAttemptElapsedRealtime: Long = 0L,
        val runningSinceElapsedRealtime: Long = 0L,
        val serverPid: Int? = null,
        val recoveryCount: Int = 0,
        val lastBinderLostElapsedRealtime: Long = 0L,
        val lastRecoveryElapsedRealtime: Long = 0L
    )

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    @Volatile private var applicationContext: Context? = null
    @Volatile private var pollingJob: Job? = null
    @Volatile private var recoveryJob: Job? = null
    @Volatile private var failedAttempts = 0
    @Volatile private var lastAttemptAt = 0L
    @Volatile private var pidJob: Job? = null
    @Volatile private var recoveryClient: AdbClient? = null
    @Volatile private var pidClient: AdbClient? = null
    @Volatile private var runningSinceAt = 0L
    @Volatile private var currentServerPid: Int? = null
    @Volatile private var recoveryCount = 0
    @Volatile private var lastBinderLostAt = 0L
    @Volatile private var lastRecoveryAt = 0L

    private val _snapshot = MutableStateFlow(Snapshot())
    val snapshot: StateFlow<Snapshot> = _snapshot.asStateFlow()

    private val binderReceivedListener = Shizuku.OnBinderReceivedListener {
        synchronized(this) {
            if (applicationContext == null || !isDesiredRunning()) return@OnBinderReceivedListener
            val now = SystemClock.elapsedRealtime()
            val newlyRunning = runningSinceAt == 0L
            if (newlyRunning) runningSinceAt = now
            if (newlyRunning && lastBinderLostAt > 0L) {
                recoveryCount++
                lastRecoveryAt = now
            }
            failedAttempts = 0
            lastAttemptAt = 0L
            recoveryJob?.cancel()
            recoveryJob = null
            recoveryClient?.close()
            publishRunning("Binder recibido; servicio activo")
            refreshServerPid()
        }
    }

    private val binderDeadListener = Shizuku.OnBinderDeadListener {
        synchronized(this) {
            if (applicationContext == null) return@OnBinderDeadListener
            pidJob?.cancel()
            pidClient?.close()
            lastBinderLostAt = SystemClock.elapsedRealtime()
            runningSinceAt = 0L
            currentServerPid = null
            publish(Stage.CHECKING_BINDER, "Binder perdido; iniciando recuperación", binderAlive = false)
            requestRecovery()
        }
    }

    @Synchronized
    fun start(context: Context) {
        if (!context.getSystemService(UserManager::class.java).isUserUnlocked) return
        applicationContext = context.applicationContext
        ensureDesiredStateInitialized(context)
        if (pollingJob?.isActive == true) return

        Shizuku.addBinderReceivedListenerSticky(binderReceivedListener)
        Shizuku.addBinderDeadListener(binderDeadListener)

        pollingJob = scope.launch {
            while (isActive) {
                synchronized(this@NightDogRecovery) {
                    if (!isActive || applicationContext == null) return@launch
                    val alive = Shizuku.pingBinder()
                    if (!isDesiredRunning()) {
                        publish(Stage.MANUALLY_STOPPED, "Servicio detenido manualmente", binderAlive = alive)
                    } else if (alive) {
                        if (runningSinceAt == 0L) runningSinceAt = SystemClock.elapsedRealtime()
                        failedAttempts = 0
                        lastAttemptAt = 0L
                        publishRunning("Binder responde correctamente")
                    } else {
                        if (_snapshot.value.binderAlive) {
                            lastBinderLostAt = SystemClock.elapsedRealtime()
                            runningSinceAt = 0L
                            currentServerPid = null
                        }
                        publish(Stage.CHECKING_BINDER, "Binder no responde; buscando transporte ADB", binderAlive = false)
                        requestRecovery()
                    }
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
    fun requestManualStart(context: Context, recoverImmediately: Boolean = true) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, true).apply()
        failedAttempts = 0
        lastAttemptAt = 0L
        publish(Stage.CHECKING_BINDER, "Inicio solicitado; comprobando Binder y ADB")
        if (recoverImmediately && !Shizuku.pingBinder()) requestRecovery()
    }

    @Synchronized
    fun prepareForManualStop(context: Context) {
        applicationContext = context.applicationContext
        preferences(context).edit().putBoolean(KEY_DESIRED_RUNNING, false).apply()
        recoveryJob?.cancel()
        recoveryJob = null
        recoveryClient?.close()
        pidJob?.cancel()
        pidClient?.close()
        failedAttempts = 0
        lastAttemptAt = 0L
        runningSinceAt = 0L
        currentServerPid = null
        publish(Stage.MANUALLY_STOPPED, "Detenido manualmente; recuperación deshabilitada")
    }

    fun prepareForManualStop() {
        val context = applicationContext ?: return
        prepareForManualStop(context)
    }

    fun isDesiredRunning(context: Context? = applicationContext): Boolean {
        val resolvedContext = context ?: return false
        if (!resolvedContext.getSystemService(UserManager::class.java).isUserUnlocked) return false
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
        recoveryClient?.close()
        pidJob?.cancel()
        pidClient?.close()
        applicationContext = null
        failedAttempts = 0
        lastAttemptAt = 0L
        runningSinceAt = 0L
        currentServerPid = null
        recoveryCount = 0
        lastBinderLostAt = 0L
        lastRecoveryAt = 0L
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

    @Synchronized
    private fun refreshServerPid() {
        val context = applicationContext ?: return
        if (!Shizuku.pingBinder() || !isDesiredRunning() || pidJob?.isActive == true) return
        // ponytail: PID is diagnostic only; discover once per Binder attachment, not every poll.
        pidJob = scope.launch {
            val operation = currentCoroutineContext()
            try {
                val endpoint = AdbTransportResolver.discover(context) ?: return@launch
                operation.ensureActive()
                if (!Shizuku.pingBinder() || !isDesiredRunning()) return@launch
                val output = ByteArrayOutputStream()
                val client = newClient(endpoint)
                synchronized(this@NightDogRecovery) {
                    operation.ensureActive()
                    pidClient = client
                }
                try {
                    client.use {
                        it.connect()
                        operation.ensureActive()
                        if (!isDesiredRunning() || !Shizuku.pingBinder()) return@launch
                        it.shellCommand("pidof shizuku_server", output::write)
                    }
                } finally {
                    if (pidClient === client) pidClient = null
                }
                val pid = output.toString(Charsets.UTF_8.name()).trim()
                    .split(Regex("\\s+")).firstOrNull()?.toIntOrNull()
                synchronized(this@NightDogRecovery) {
                    operation.ensureActive()
                    if (pid != null && isDesiredRunning() && Shizuku.pingBinder()) {
                        currentServerPid = pid
                        publishRunning("Binder responde correctamente")
                    }
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                // ponytail: ADB diagnostics must not downgrade a healthy Binder.
                Log.d("NightDogRecovery", "PID no disponible: ${error.javaClass.simpleName}")
            }
        }
    }

    private fun newClient(endpoint: AdbEndpoint): AdbClient {
        val key = AdbKey(PreferenceAdbKeyStore(ShizukuSettings.getPreferences()), "shizuku")
        return AdbClient(endpoint.host, endpoint.port, key, requireTls = true)
    }

    @Synchronized
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
            val operation = currentCoroutineContext()
            try {
                delay(RECOVERY_SETTLE_MS)
                if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch
                val context = applicationContext ?: return@launch
                synchronized(this@NightDogRecovery) {
                    operation.ensureActive()
                    if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch
                    failedAttempts++
                    lastAttemptAt = SystemClock.elapsedRealtime()
                    publish(Stage.DISCOVERING_ADB, "Buscando el puerto ADB actual; aún no autenticado",
                        transport = null, endpoint = null)
                }
                val endpoint = AdbTransportResolver.discover(context)
                operation.ensureActive()
                if (Shizuku.pingBinder() || !isDesiredRunning()) return@launch
                if (endpoint == null) {
                    publish(Stage.WAITING_FOR_ADB,
                        "Sin anuncio ADB actual. Comprueba Wi-Fi, depuración inalámbrica y emparejamiento",
                        transport = "ninguno", endpoint = null)
                    return@launch
                }
                val client = newClient(endpoint)
                synchronized(this@NightDogRecovery) {
                    operation.ensureActive()
                    if (!isDesiredRunning() || Shizuku.pingBinder()) return@launch
                    recoveryClient = client
                }
                try {
                    client.use {
                        it.connect()
                        operation.ensureActive()
                        if (!isDesiredRunning() || Shizuku.pingBinder()) return@launch
                        publish(Stage.STARTING_SERVICE, "ADB autenticado; iniciando servicio sin actividad",
                            transport = "ADB autenticado", endpoint = "${endpoint.host}:${endpoint.port}")
                        operation.ensureActive()
                        if (!isDesiredRunning() || Shizuku.pingBinder()) return@launch
                        it.shellCommand(Starter.internalCommand, null)
                    }
                } finally {
                    if (recoveryClient === client) recoveryClient = null
                }
                delay(RECOVERY_SETTLE_MS)
                operation.ensureActive()
                if (Shizuku.pingBinder()) {
                    publishRunning("Binder verificado tras el inicio")
                } else {
                    publish(Stage.WAITING_FOR_ADB, "Inicio enviado, pero Binder aún no responde; se reintentará")
                }
            } catch (cancelled: CancellationException) {
                throw cancelled
            } catch (error: Exception) {
                operation.ensureActive()
                if (isDesiredRunning() && !Shizuku.pingBinder()) {
                    Log.w("NightDogRecovery", "Recuperación fallida: ${error.javaClass.simpleName}")
                    publish(Stage.ERROR, "Falló la recuperación ADB: ${error.javaClass.simpleName}. Comprueba la autorización")
                }
            }
        }
    }

    private fun publishRunning(result: String) {
        if (!Shizuku.pingBinder()) return
        publish(
            Stage.RUNNING,
            result,
            binderAlive = true,
            transport = "Binder activo",
            endpoint = "no requerido"
        )
    }

    @Synchronized
    private fun publish(
        stage: Stage,
        result: String,
        binderAlive: Boolean = Shizuku.pingBinder(),
        transport: String? = _snapshot.value.transport,
        endpoint: String? = _snapshot.value.endpoint
    ) {
        if (applicationContext == null) return
        _snapshot.value = Snapshot(
            stage = if (isDesiredRunning()) stage else Stage.MANUALLY_STOPPED,
            desiredRunning = isDesiredRunning(),
            binderAlive = binderAlive,
            transport = transport,
            endpoint = endpoint,
            failedAttempts = failedAttempts,
            lastResult = if (isDesiredRunning()) result else "Detenido manualmente; recuperación deshabilitada",
            lastAttemptElapsedRealtime = lastAttemptAt,
            runningSinceElapsedRealtime = runningSinceAt,
            serverPid = currentServerPid,
            recoveryCount = recoveryCount,
            lastBinderLostElapsedRealtime = lastBinderLostAt,
            lastRecoveryElapsedRealtime = lastRecoveryAt
        )
    }
}
