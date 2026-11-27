package moe.shizuku.manager.shizuku

import java.net.ConnectException
import java.net.SocketTimeoutException
import java.util.concurrent.atomic.AtomicInteger
import javax.net.ssl.SSLHandshakeException
import javax.net.ssl.SSLProtocolException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import moe.shizuku.manager.adb.AdbAuthorizationException
import moe.shizuku.manager.adb.AdbDiscoveryResult
import moe.shizuku.manager.adb.AdbEndpoint
import moe.shizuku.manager.adb.AdbKeyException
import moe.shizuku.manager.adb.AdbOfflineException
import moe.shizuku.manager.adb.AdbShellUnavailableException
import moe.shizuku.manager.adb.WirelessDebuggingRestoreResult
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class NightDogRecoveryEngineTest {

    private class FakeSession(
        val endpoint: AdbEndpoint,
        private val onConnect: () -> Unit = {},
        private val onVerifyShell: () -> Boolean = { true },
        private val onStartServer: () -> Unit = {},
        private val onQueryPid: () -> Int? = { 4242 }
    ) : RecoveryAdbSession {
        var connectCalls = 0
        var verifyShellCalls = 0
        var startServerCalls = 0
        var closeCalls = 0
        val events = mutableListOf<String>()

        override fun connect() {
            connectCalls++
            events.add("connect")
            onConnect()
        }

        override fun verifyShell(): Boolean {
            verifyShellCalls++
            events.add("verifyShell")
            return onVerifyShell()
        }

        override fun startServer() {
            startServerCalls++
            events.add("startServer")
            onStartServer()
        }

        override fun queryServerPid(): Int? {
            events.add("queryPid")
            return onQueryPid()
        }

        override fun close() {
            closeCalls++
            events.add("close")
        }
    }

    @Test
    fun redescubrePuertoNuevoEnCadaIntentoVerificaShellYConfirmaBinderAntesDeRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            var binderAlive = false
            val discoveredPorts = ArrayDeque(listOf(37111, 41222))
            val openedSessions = mutableListOf<FakeSession>()
            var attemptCounter = 0

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    val port = discoveredPorts.removeFirst()
                    AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", port, requireTls = true))
                },
                openSession = { endpoint ->
                    attemptCounter++
                    val currentAttempt = attemptCounter
                    FakeSession(
                        endpoint = endpoint,
                        onConnect = {
                            if (currentAttempt == 1) throw ConnectException("port closed after Wi-Fi switch")
                        },
                        onVerifyShell = { true },
                        onStartServer = {
                            // Binder responds only after startServer executes on attempt 2
                            binderAlive = true
                        }
                    ).also { openedSessions.add(it) }
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L,
                binderConfirmTimeoutMs = 2_000L,
                binderConfirmPollMs = 200L
            )

            engine.start(startPollingLoop = false)
            // First attempt fails offline on port 37111
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.FailureCategory.OFFLINE, engine.snapshot.value.failureCategory)
            assertEquals(1, engine.snapshot.value.failedAttempts)
            assertEquals(1, openedSessions[0].closeCalls)
            assertEquals(37111, openedSessions[0].endpoint.port)

            // Polling before 8s backoff must NOT launch attempt 2 and must preserve failure category
            nowMs += 4_000L
            engine.pollOnce()
            assertEquals(1, openedSessions.size)
            assertEquals(NightDogRecovery.FailureCategory.OFFLINE, engine.snapshot.value.failureCategory)

            // After 8s backoff, attempt 2 rediscovers fresh port 41222, verifies shell BEFORE start, confirms Binder
            nowMs += 4_000L
            engine.pollOnce()
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(2, openedSessions.size)
            val second = openedSessions[1]
            assertEquals(41222, second.endpoint.port)
            assertEquals(listOf("connect", "verifyShell", "startServer", "close"), second.events)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.binderAlive)
            assertEquals(0, engine.snapshot.value.failedAttempts)
            assertEquals(NightDogRecovery.FailureCategory.NONE, engine.snapshot.value.failureCategory)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noIniciaServicioSiVerifyShellFallaYCierraConexion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            lateinit var session: FakeSession

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = { AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 39000)) },
                openSession = { endpoint ->
                    FakeSession(endpoint = endpoint, onVerifyShell = { false }).also { session = it }
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, session.connectCalls)
            assertEquals(1, session.verifyShellCalls)
            assertEquals("No debe intentar iniciar Nightzuku sin shell verificado", 0, session.startServerCalls)
            assertEquals(1, session.closeCalls)
            assertEquals(NightDogRecovery.Stage.ERROR, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE, engine.snapshot.value.failureCategory)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun noDeclaraRunningSiBinderNoRespondeTrasEnviarInicio() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            lateinit var session: FakeSession

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = { AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 39001)) },
                openSession = { endpoint -> FakeSession(endpoint = endpoint).also { session = it } },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L,
                binderConfirmTimeoutMs = 1_000L,
                binderConfirmPollMs = 200L
            )

            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, session.startServerCalls)
            assertEquals(1, session.closeCalls)
            assertNotEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.FailureCategory.BINDER_NOT_CONFIRMED, engine.snapshot.value.failureCategory)
            assertFalse(engine.snapshot.value.binderAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun eventosDuplicadosNoDisparanRecuperacionesSimultaneas() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            var binderAlive = false
            val discoveryCalls = AtomicInteger(0)
            val discoveryStarted = CompletableDeferred<Unit>()
            val releaseDiscovery = CompletableDeferred<AdbDiscoveryResult>()
            val startServerCalls = AtomicInteger(0)

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    val callNumber = discoveryCalls.incrementAndGet()
                    if (callNumber == 1) {
                        discoveryStarted.complete(Unit)
                        releaseDiscovery.await()
                    } else {
                        // Post-recovery diagnostic PID lookup
                        AdbDiscoveryResult.Absent
                    }
                },
                openSession = { endpoint ->
                    FakeSession(
                        endpoint = endpoint,
                        onStartServer = {
                            startServerCalls.incrementAndGet()
                            binderAlive = true
                        }
                    )
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            discoveryStarted.await()

            // Duplicate triggers while attempt 1 is in flight
            engine.onBinderDead()
            engine.pollOnce()
            engine.onBinderDead()
            engine.requestManualStart(recoverImmediately = true)
            engine.pollOnce()

            assertEquals("Debe existir solo un descubrimiento activo", 1, discoveryCalls.get())

            releaseDiscovery.complete(AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 40123)))
            engine.activeRecoveryJobForTest()?.join()

            assertEquals("Debe ejecutarse exactamente un arranque de servidor", 1, startServerCalls.get())
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun paradaManualDuranteIntentoCancelaDescartaCallbackTardioYCierraConexion() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            var binderAlive = false
            val connectEntered = CompletableDeferred<Unit>()
            val releaseConnect = CompletableDeferred<Unit>()
            lateinit var session: FakeSession

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = { AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 40555)) },
                openSession = { endpoint ->
                    FakeSession(
                        endpoint = endpoint,
                        onConnect = {
                            connectEntered.complete(Unit)
                            runBlocking { releaseConnect.await() }
                        }
                    ).also { session = it }
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            connectEntered.await()
            val cancelledJob = engine.activeRecoveryJobForTest()

            // Manual stop while ADB connect is blocked
            engine.prepareForManualStop()

            assertFalse(desired)
            assertEquals(NightDogRecovery.Stage.MANUALLY_STOPPED, engine.snapshot.value.stage)
            assertTrue("Debe cerrar la conexión ADB inmediatamente al parar manualmente", session.closeCalls >= 1)

            // Unblock late connect callback from cancelled attempt
            releaseConnect.complete(Unit)
            cancelledJob?.join()

            assertEquals("Un intento cancelado no debe iniciar el servidor", 0, session.startServerCalls)
            assertEquals(NightDogRecovery.Stage.MANUALLY_STOPPED, engine.snapshot.value.stage)

            // Late Binder arriving after manual stop must NOT re-enable desiredRunning
            binderAlive = true
            engine.onBinderReceived()
            assertFalse(desired)
            assertEquals(NightDogRecovery.Stage.MANUALLY_STOPPED, engine.snapshot.value.stage)
            assertFalse(engine.snapshot.value.desiredRunning)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun descartaResultadoTardioDeDescubrimientoCancelado() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            val discoveryEntered = CompletableDeferred<Unit>()
            val sessionsOpened = AtomicInteger(0)

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = {
                    discoveryEntered.complete(Unit)
                    // Non-cancellable late return simulating an NSD callback arriving right after stop
                    AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 40999))
                },
                openSession = { endpoint ->
                    sessionsOpened.incrementAndGet()
                    FakeSession(endpoint)
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            // Use a deferred that ignores coroutine cancellation to simulate a late NSD callback
            val lateDiscovery = CompletableDeferred<AdbDiscoveryResult>()
            val lateEntered = CompletableDeferred<Unit>()
            val lateEngine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = {
                    lateEntered.complete(Unit)
                    try {
                        lateDiscovery.await()
                    } catch (_: Throwable) {
                        AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 40999))
                    }
                },
                openSession = { endpoint ->
                    sessionsOpened.incrementAndGet()
                    FakeSession(endpoint)
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            lateEngine.start(startPollingLoop = false)
            lateEntered.await()
            val job = lateEngine.activeRecoveryJobForTest()
            lateEngine.prepareForManualStop()
            lateDiscovery.complete(AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 40999)))
            job?.join()

            assertEquals("Callback tardío de descubrimiento cancelado no debe abrir sesión", 0, sessionsOpened.get())
            assertEquals(NightDogRecovery.Stage.MANUALLY_STOPPED, lateEngine.snapshot.value.stage)
            engine.stop()
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun distingueDepuracionDesactivadaAusenteOfflineYAutorizacionRevocadaYBackoff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 10_000L
            var desired = true
            var discoveryOutcome: AdbDiscoveryResult = AdbDiscoveryResult.WirelessDebuggingDisabled
            var connectFailure: Throwable? = null

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = { discoveryOutcome },
                openSession = { endpoint ->
                    FakeSession(endpoint, onConnect = { connectFailure?.let { throw it } })
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            // Backoff curve checks: 8s, 16s, 32s, 60s, 60s
            assertEquals(0L, engine.retryDelayMs(0))
            assertEquals(8_000L, engine.retryDelayMs(1))
            assertEquals(16_000L, engine.retryDelayMs(2))
            assertEquals(32_000L, engine.retryDelayMs(3))
            assertEquals(60_000L, engine.retryDelayMs(4))
            assertEquals(60_000L, engine.retryDelayMs(10))

            // 1. Wireless debugging disabled
            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)

            // 2. Endpoint absent after 8s backoff
            nowMs += 8_000L
            discoveryOutcome = AdbDiscoveryResult.Absent
            engine.pollOnce()
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.FailureCategory.ENDPOINT_ABSENT, engine.snapshot.value.failureCategory)

            // 3. Offline after 16s backoff
            nowMs += 16_000L
            discoveryOutcome = AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 38888))
            connectFailure = SocketTimeoutException("timed out")
            engine.pollOnce()
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.FailureCategory.OFFLINE, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)

            // 4. Auth revoked after 32s backoff
            nowMs += 32_000L
            connectFailure = SSLProtocolException("certificate rejected")
            engine.pollOnce()
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.Stage.ERROR, engine.snapshot.value.stage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun clasificadorDeErroresAdbEsEstrictoYNoFalsificaAutorizacion() {
        assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(SSLProtocolException("bad_certificate")))
        assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(SSLHandshakeException("handshake failed")))
        assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(AdbAuthorizationException("revoked")))
        assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(AdbKeyException(RuntimeException("keystore"))))
        assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(ConnectException("refused")))
        assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(SocketTimeoutException("timeout")))
        assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(AdbOfflineException("offline")))
        assertEquals(NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE, AdbFailureClassifier.classify(AdbShellUnavailableException("closed")))
        assertEquals(NightDogRecovery.FailureCategory.UNKNOWN, AdbFailureClassifier.classify(IllegalStateException("other")))
    }

    private class FakeSystemEnv(
        var wifiConnected: Boolean = true,
        var localPersistentTcp: Boolean = false,
        var autoRestoreWireless: Boolean = false,
        var restoreResult: WirelessDebuggingRestoreResult = WirelessDebuggingRestoreResult.NotPermitted,
        var batteryIgnored: Boolean? = true
    ) : NightDogSystemEnvironment {
        var restoreAttempts = 0
        var confirmedRunningCalls = 0
        var onTryRestore: (() -> WirelessDebuggingRestoreResult)? = null

        override fun isWifiConnected(): Boolean = wifiConnected
        override fun hasLocalPersistentTcpEndpoint(): Boolean = localPersistentTcp
        override fun isAutoRestoreWirelessEnabled(): Boolean = autoRestoreWireless
        override fun tryEnableWirelessDebugging(): WirelessDebuggingRestoreResult {
            restoreAttempts++
            return onTryRestore?.invoke() ?: restoreResult
        }
        override fun isIgnoringBatteryOptimizations(): Boolean? = batteryIgnored
        override fun onServiceConfirmedRunning() {
            confirmedRunningCalls++
        }
    }

    private class FakeSchedulerPort : NightDogSchedulerPort {
        val networkRecoveryDelays = mutableListOf<Long>()
        var wirelessSettingTriggerCalls = 0
        var periodicHealthCalls = 0
        var cancelRecoveryRetriesCalls = 0
        var cancelAllJobsCalls = 0
        val notifications = mutableListOf<Triple<NightDogRecovery.FailureCategory, NightDogRecovery.ActionHint, String>>()
        var clearNotificationsCalls = 0

        override fun scheduleNetworkRecovery(delayMs: Long) {
            networkRecoveryDelays.add(delayMs)
        }

        override fun scheduleWirelessSettingTrigger() {
            wirelessSettingTriggerCalls++
        }

        override fun schedulePeriodicHealthCheck() {
            periodicHealthCalls++
        }

        override fun cancelRecoveryRetries() {
            cancelRecoveryRetriesCalls++
        }

        override fun cancelAllJobs() {
            cancelAllJobsCalls++
        }

        override fun notifyActionRequired(
            category: NightDogRecovery.FailureCategory,
            actionHint: NightDogRecovery.ActionHint,
            message: String
        ) {
            notifications.add(Triple(category, actionHint, message))
        }

        override fun clearActionNotification() {
            clearNotificationsCalls++
        }
    }

    @Test
    fun wifiDesconectadoYReconectadoConservaDesiredRunningYRedescubreSinEsperarBackoff() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 10_000L
            var desired = true
            var binderAlive = false
            val env = FakeSystemEnv(wifiConnected = false)
            val sched = FakeSchedulerPort()
            val discoveryCalls = AtomicInteger(0)

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    discoveryCalls.incrementAndGet()
                    AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.70", 42100))
                },
                openSession = { endpoint ->
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            // Sin Wi-Fi: no desperdicia escaneo mDNS, conserva desiredRunning y programa job de red
            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertTrue(desired)
            assertEquals(0, discoveryCalls.get())
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.FailureCategory.WIFI_DISCONNECTED, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.ActionHint.CONNECT_WIFI, engine.snapshot.value.actionHint)
            assertFalse(engine.snapshot.value.binderAlive)
            assertTrue(sched.networkRecoveryDelays.isNotEmpty())

            // Reconexión Wi-Fi inmediata (sin esperar backoff de 8s): redescubre y confirma Binder
            nowMs += 1_000L
            env.wifiConnected = true
            engine.onNetworkChanged(wifiConnected = true, networkFingerprint = "wifi:192.168.1.70")
            engine.activeRecoveryJobForTest()?.join()

            assertTrue(desired)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.binderAlive)
            assertTrue(sched.cancelRecoveryRetriesCalls >= 1)
            assertTrue(sched.clearNotificationsCalls >= 1)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun cambioDeRedOIpRedescubrePuertoFrescoYNoPerturbaBinderYaActivo() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 5_000L
            var desired = true
            var binderAlive = false
            val env = FakeSystemEnv(wifiConnected = true)
            val sched = FakeSchedulerPort()
            val endpoints = ArrayDeque(
                listOf(
                    AdbDiscoveryResult.Absent,
                    AdbDiscoveryResult.Found(AdbEndpoint("10.0.0.42", 44999))
                )
            )
            val openedEndpoints = mutableListOf<AdbEndpoint>()

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    if (endpoints.isNotEmpty()) endpoints.removeFirst() else AdbDiscoveryResult.Absent
                },
                openSession = { endpoint ->
                    openedEndpoints.add(endpoint)
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.FailureCategory.ENDPOINT_ABSENT, engine.snapshot.value.failureCategory)
            assertEquals(0, openedEndpoints.size)

            // Cambio de red / IP (AP distinto) antes de agotar el backoff -> dispara descubrimiento inmediato
            nowMs += 2_000L
            engine.onNetworkChanged(wifiConnected = true, networkFingerprint = "wifi:192.168.1.10")
            engine.activeRecoveryJobForTest()?.join()
            // Si aún estaba ausente, cambiamos a la segunda red
            nowMs += 2_000L
            engine.onNetworkChanged(wifiConnected = true, networkFingerprint = "wifi:10.0.0.42")
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, openedEndpoints.size)
            assertEquals("10.0.0.42", openedEndpoints[0].host)
            assertEquals(44999, openedEndpoints[0].port)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)

            // Otro cambio de red mientras Binder ya está activo NO abre sesiones ni cambia estado
            nowMs += 5_000L
            engine.onNetworkChanged(wifiConnected = true, networkFingerprint = "wifi:172.16.0.8")
            assertEquals(1, openedEndpoints.size)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun autoRestauracionDepuracionInalambricaSoloConPermisoYRespetaDesactivacionManual() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 20_000L
            var desired = true
            var binderAlive = false
            var wirelessEnabled = false
            val env = FakeSystemEnv(wifiConnected = true, autoRestoreWireless = true).apply {
                onTryRestore = {
                    wirelessEnabled = true
                    WirelessDebuggingRestoreResult.Enabled
                }
            }
            val sched = FakeSchedulerPort()

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    if (!wirelessEnabled) {
                        AdbDiscoveryResult.WirelessDebuggingDisabled
                    } else {
                        AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 43210))
                    }
                },
                openSession = { endpoint ->
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L,
                wirelessEnableSettleMs = 0L
            )

            // 1. Con permiso WRITE_SECURE_SETTINGS (simulado por Enabled) y sin desactivación manual -> recupera automáticamente
            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, env.restoreAttempts)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.wirelessAutoRestoreAttempted)

            // 2. Usuario desactiva explícitamente depuración inalámbrica con Wi-Fi estable -> NO reactivar contra su decisión
            nowMs += 70_000L
            binderAlive = false
            wirelessEnabled = false
            engine.onWirelessDebuggingSettingChanged(enabled = false)
            assertTrue(engine.isWirelessDisabledByUserForTest())
            engine.onBinderDead()
            engine.activeRecoveryJobForTest()?.join()

            assertEquals("No debe reactivar adb_wifi_enabled tras desactivación manual del usuario", 1, env.restoreAttempts)
            assertEquals(NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING, engine.snapshot.value.actionHint)
            assertTrue("desiredRunning se conserva aunque depuración inalámbrica esté apagada", desired)
            assertTrue(sched.wirelessSettingTriggerCalls >= 1)

            // 3. Usuario vuelve a activar depuración inalámbrica -> redescubrimiento y recuperación inmediata
            nowMs += 1_000L
            wirelessEnabled = true
            engine.onWirelessDebuggingSettingChanged(enabled = true)
            engine.activeRecoveryJobForTest()?.join()

            assertFalse(engine.isWirelessDisabledByUserForTest())
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.binderAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun diagnosticoClaroYNotificacionCuandoAndroidRechazaOExigeIntervencionManual() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 10_000L
            var desired = true
            val env = FakeSystemEnv(
                wifiConnected = true,
                autoRestoreWireless = true,
                restoreResult = WirelessDebuggingRestoreResult.SystemRejected("SecurityException")
            )
            val sched = FakeSchedulerPort()

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { false },
                discoverEndpoint = { AdbDiscoveryResult.WirelessDebuggingDisabled },
                openSession = { FakeSession(it) },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, env.restoreAttempts)
            assertEquals(NightDogRecovery.Stage.WAITING_FOR_ADB, engine.snapshot.value.stage)
            assertEquals(NightDogRecovery.FailureCategory.WIRELESS_DEBUGGING_DISABLED, engine.snapshot.value.failureCategory)
            assertEquals(NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING, engine.snapshot.value.actionHint)
            assertFalse(engine.snapshot.value.binderAlive)
            assertEquals(1, sched.notifications.size)
            assertEquals(NightDogRecovery.ActionHint.OPEN_WIRELESS_DEBUGGING, sched.notifications[0].second)
            assertTrue(sched.wirelessSettingTriggerCalls >= 1)

            // Segundo intento en el mismo ciclo de red no reintenta putInt repetidamente tras rechazo del sistema
            nowMs += 70_000L
            engine.pollOnce()
            engine.activeRecoveryJobForTest()?.join()
            assertEquals("No debe spamear escritura rechazada por el sistema", 1, env.restoreAttempts)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun binderTardioConPingFalsoNoCancelaRecuperacionNiDeclaraRunning() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            var desired = true
            var binderAlive = false
            val discoveryEntered = CompletableDeferred<Unit>()
            val releaseDiscovery = CompletableDeferred<AdbDiscoveryResult>()

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    discoveryEntered.complete(Unit)
                    releaseDiscovery.await()
                },
                openSession = { endpoint ->
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            discoveryEntered.await()

            // Callback onBinderReceived tardío cuando pingBinder() sigue siendo false
            engine.onBinderReceived()
            assertNotEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue("El intento activo no debe cancelarse ante Binder muerto", engine.activeRecoveryJobForTest()?.isActive == true)

            releaseDiscovery.complete(AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.50", 41888)))
            engine.activeRecoveryJobForTest()?.join()
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.binderAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reinicioDeProcesoRecuperacionPostBootYRestriccionesDeBateria() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 1_000L
            // Estado persistido en SharedPreferences sobrevive al reinicio del proceso
            var persistedDesired = true
            var binderAlive = false
            val sched = FakeSchedulerPort()
            val env = FakeSystemEnv(
                wifiConnected = true,
                autoRestoreWireless = true,
                restoreResult = WirelessDebuggingRestoreResult.Enabled,
                batteryIgnored = false
            )

            // 1. Proceso original detenido inesperadamente (kill por MagicOS) -> desiredRunning sigue true
            val process1 = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { persistedDesired },
                setDesiredRunning = { persistedDesired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = { AdbDiscoveryResult.Absent },
                openSession = { FakeSession(it) },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )
            process1.start(startPollingLoop = false)
            process1.activeRecoveryJobForTest()?.join()
            assertEquals(false, process1.snapshot.value.batteryOptimizationIgnored)
            assertTrue(persistedDesired)
            // Simula terminación del proceso sin parada manual
            process1.stop()
            assertTrue("Terminar el proceso no debe borrar desiredRunning persistido", persistedDesired)

            // 2. Nuevo proceso tras reinicio / boot desbloqueado / wakeup por JobScheduler o Doze
            var wirelessRestoredAfterBoot = false
            env.onTryRestore = {
                wirelessRestoredAfterBoot = true
                WirelessDebuggingRestoreResult.Enabled
            }
            val process2 = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { persistedDesired },
                setDesiredRunning = { persistedDesired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    if (!wirelessRestoredAfterBoot) {
                        AdbDiscoveryResult.WirelessDebuggingDisabled
                    } else {
                        AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.60", 45123))
                    }
                },
                openSession = { endpoint ->
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L,
                wirelessEnableSettleMs = 0L
            )
            process2.start(startPollingLoop = false)
            process2.onBootUnlocked()
            process2.activeRecoveryJobForTest()?.join()

            assertEquals(NightDogRecovery.Stage.RUNNING, process2.snapshot.value.stage)
            assertTrue(process2.snapshot.value.binderAlive)
            assertEquals(false, process2.snapshot.value.batteryOptimizationIgnored)
            assertTrue(sched.periodicHealthCalls >= 1)

            // 3. Parada manual prevalece sobre boot, red, reanudación y wakeups programados
            process2.prepareForManualStop()
            assertFalse(persistedDesired)
            assertTrue(sched.cancelAllJobsCalls >= 1)

            binderAlive = false
            process2.onBootUnlocked()
            process2.onNetworkChanged(true, "wifi:192.168.1.99")
            process2.onDeviceResume()
            process2.onScheduledWakeup("vigilancia periódica")
            process2.onWirelessDebuggingSettingChanged(true)
            assertEquals(NightDogRecovery.Stage.MANUALLY_STOPPED, process2.snapshot.value.stage)
            assertFalse(persistedDesired)
            assertEquals(null, process2.activeRecoveryJobForTest())
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun reanudacionDeSuspensionYEventosConcurrentesMantienenIntentoUnico() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var nowMs = 10_000L
            var desired = true
            var binderAlive = false
            val env = FakeSystemEnv(wifiConnected = true)
            val sched = FakeSchedulerPort()
            val discoveryCalls = AtomicInteger(0)
            val discoveryStarted = CompletableDeferred<Unit>()
            val releaseDiscovery = CompletableDeferred<AdbDiscoveryResult>()

            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { nowMs },
                delayMs = { ms -> nowMs += ms },
                isDesiredRunning = { desired },
                setDesiredRunning = { desired = it },
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    if (discoveryCalls.incrementAndGet() == 1) {
                        discoveryStarted.complete(Unit)
                        releaseDiscovery.await()
                    } else {
                        AdbDiscoveryResult.Absent
                    }
                },
                openSession = { endpoint ->
                    FakeSession(endpoint, onStartServer = { binderAlive = true })
                },
                systemEnv = env,
                scheduler = sched,
                logWarning = {},
                logDebug = {},
                recoverySettleMs = 0L
            )

            engine.start(startPollingLoop = false)
            discoveryStarted.await()

            // Tormenta de eventos concurrentes (red, reanudación, job, binder dead, poll)
            engine.onNetworkChanged(true, "wifi:192.168.1.88")
            engine.onDeviceResume()
            engine.onScheduledWakeup("red disponible")
            engine.onBinderDead()
            engine.pollOnce()

            assertEquals("Solo debe existir un descubrimiento en vuelo", 1, discoveryCalls.get())

            releaseDiscovery.complete(AdbDiscoveryResult.Found(AdbEndpoint("192.168.1.88", 46001)))
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
            assertTrue(engine.snapshot.value.binderAlive)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun puertoTlsObsoletoSeRecuperaEnElMismoIntentoConOtroPuertoTls() = runBlocking {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
        try {
            var binderAlive = false
            val previous = AdbEndpoint("192.168.1.88", 36001)
            val fresh = AdbEndpoint("192.168.2.88", 46002)
            val staleSession = FakeSession(previous, onConnect = { throw ConnectException("puerto anterior") })
            val freshSession = FakeSession(fresh, onStartServer = { binderAlive = true })
            val alternateCalls = AtomicInteger(0)
            val engine = NightDogRecoveryEngine(
                scope = scope,
                clockElapsedMs = { 1_000L },
                delayMs = {},
                isDesiredRunning = { true },
                setDesiredRunning = {},
                pingBinder = { binderAlive },
                discoverEndpoint = {
                    if (binderAlive) AdbDiscoveryResult.Absent else AdbDiscoveryResult.Found(previous)
                },
                openSession = { if (it == previous) staleSession else freshSession },
                discoverAlternateEndpoint = {
                    assertEquals(previous, it)
                    alternateCalls.incrementAndGet()
                    fresh
                },
                systemEnv = FakeSystemEnv(wifiConnected = true, localPersistentTcp = false),
                recoverySettleMs = 0L,
                logWarning = {},
                logDebug = {}
            )

            engine.start(startPollingLoop = false)
            engine.activeRecoveryJobForTest()?.join()

            assertEquals(1, alternateCalls.get())
            assertEquals(1, staleSession.closeCalls)
            assertEquals(0, staleSession.startServerCalls)
            assertEquals(listOf("connect", "verifyShell", "startServer", "close"), freshSession.events)
            assertEquals(NightDogRecovery.Stage.RUNNING, engine.snapshot.value.stage)
        } finally {
            scope.cancel()
        }
    }
}
