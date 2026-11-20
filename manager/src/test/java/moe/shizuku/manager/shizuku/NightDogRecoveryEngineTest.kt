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
}
