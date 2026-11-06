package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class PersistenceUiMapperTest {

    private val usableTcp = TcpHealth(
        state = TcpHealthState.ENABLED_REACHABLE,
        capability = TcpCapability.USABLE_FOR_RESTART,
        endpoint = "127.0.0.1:5555"
    )

    @Test
    fun mapsRunningAndStopped() {
        assertEquals(
            PersistenceServiceState.RUNNING,
            PersistenceUiMapper.mapService(true, true, "CHECKING_BINDER")
        )
        assertEquals(
            PersistenceServiceState.MANUALLY_STOPPED,
            PersistenceUiMapper.mapService(false, false, "IDLE")
        )
        assertEquals(
            PersistenceServiceState.MANUALLY_STOPPED,
            PersistenceUiMapper.mapService(true, false, "MANUALLY_STOPPED")
        )
    }

    @Test
    fun mapsWaitingRecoveringAndError() {
        assertEquals(
            PersistenceServiceState.WAITING_FOR_ADB,
            PersistenceUiMapper.mapService(true, false, "WAITING_FOR_ADB")
        )
        assertEquals(
            PersistenceServiceState.RECOVERING,
            PersistenceUiMapper.mapService(true, false, "DISCOVERING_ADB")
        )
        assertEquals(
            PersistenceServiceState.ERROR,
            PersistenceUiMapper.mapService(true, false, "ERROR")
        )
    }

    @Test
    fun claimsLocalTcpOnlyWhenAuthenticated() {
        val model = PersistenceUiMapper.map(
            desiredRunning = true,
            binderAlive = false,
            stage = "WAITING_FOR_ADB",
            snapshotEndpoint = null,
            serverPid = null,
            recoveryCount = 2,
            lastResultKey = "no_usable_endpoint",
            lastFailure = "No usable ADB endpoint",
            retryRemainingMs = 8_000L,
            reactivationRequired = false,
            tcp = usableTcp,
            displayTransport = TransportCandidate(
                RecoveryTransport.PERSISTENT_LOCAL_TCP,
                "127.0.0.1",
                5555,
                configured = true,
                socketReachable = true,
                authenticated = true
            )
        )
        assertTrue(model.localTcpRecoveryAvailable)
        assertFalse(model.wirelessActivationRequired)
        assertEquals("127.0.0.1:5555", model.endpoint)
        assertEquals(8_000L, model.retryRemainingMs)
    }

    @Test
    fun requiresWirelessDebuggingWhenTcpIsNotUsable() {
        val model = PersistenceUiMapper.map(
            desiredRunning = true,
            binderAlive = false,
            stage = "WAITING_FOR_ADB",
            snapshotEndpoint = null,
            serverPid = null,
            recoveryCount = 0,
            lastResultKey = "no_usable_endpoint",
            lastFailure = null,
            retryRemainingMs = 0L,
            reactivationRequired = true,
            tcp = TcpHealth(TcpHealthState.ENABLED_UNREACHABLE, TcpCapability.CONFIGURED, "127.0.0.1:5555"),
            displayTransport = TransportCandidate(RecoveryTransport.NONE)
        )
        assertEquals(PersistenceServiceState.WAITING_FOR_ADB, model.service)
        assertFalse(model.localTcpRecoveryAvailable)
        assertTrue(model.wirelessActivationRequired)
    }
}
