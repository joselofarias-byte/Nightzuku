package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryTransportPolicyTest {

    private val persistent = TransportCandidate(
        kind = RecoveryTransport.PERSISTENT_LOCAL_TCP,
        host = "127.0.0.1",
        port = 5555,
        configured = true,
        socketReachable = true,
        authenticated = true
    )
    private val mdns = TransportCandidate(
        kind = RecoveryTransport.MDNS_WIRELESS_DEBUGGING,
        host = "192.168.1.20",
        port = 37000,
        configured = true,
        socketReachable = true
    )
    private val systemTcp = TransportCandidate(
        kind = RecoveryTransport.SYSTEM_ADB_TCP,
        host = "127.0.0.1",
        port = 5555,
        configured = true,
        socketReachable = true,
        authenticated = true
    )

    @Test
    fun displayPrefersLiveBinder() {
        val selected = RecoveryTransportPolicy.selectForDisplay(true, persistent, mdns, systemTcp)
        assertEquals(RecoveryTransport.BINDER_ALIVE, selected.kind)
        assertTrue(selected.usableForRestart)
    }

    @Test
    fun displayUsesAuthenticatedPersistentTcpBeforeMdns() {
        val selected = RecoveryTransportPolicy.selectForDisplay(false, persistent, mdns, systemTcp)
        assertEquals(RecoveryTransport.PERSISTENT_LOCAL_TCP, selected.kind)
        assertEquals("127.0.0.1:5555", selected.endpoint)
        assertTrue(RecoveryTransportPolicy.localTcpRecoveryAvailable(persistent))
    }

    @Test
    fun displayDoesNotClaimDeadPersistentTcp() {
        val dead = persistent.copy(socketReachable = false, authenticated = false)
        val selected = RecoveryTransportPolicy.selectForDisplay(false, dead, mdns, systemTcp)
        assertEquals(RecoveryTransport.MDNS_WIRELESS_DEBUGGING, selected.kind)
        assertFalse(RecoveryTransportPolicy.localTcpRecoveryAvailable(dead))
    }

    @Test
    fun displayFallsBackToAuthenticatedSystemTcp() {
        val selected = RecoveryTransportPolicy.selectForDisplay(false, null, null, systemTcp)
        assertEquals(RecoveryTransport.SYSTEM_ADB_TCP, selected.kind)
    }

    @Test
    fun displayDoesNotClaimUnusableSystemTcp() {
        val unusable = systemTcp.copy(authenticated = false, socketReachable = true)
        val selected = RecoveryTransportPolicy.selectForDisplay(false, null, null, unusable)
        assertEquals(RecoveryTransport.NONE, selected.kind)
        assertFalse(selected.usableForRestart)
    }

    @Test
    fun recoveryAttemptSkipsUnreachablePersistentTcp() {
        val dead = persistent.copy(socketReachable = false, authenticated = false)
        val selected = RecoveryTransportPolicy.selectForRecoveryAttempt(dead, mdns, systemTcp)
        assertEquals(RecoveryTransport.MDNS_WIRELESS_DEBUGGING, selected.kind)
    }

    @Test
    fun recoveryAttemptCanTryReachableSystemTcp() {
        val selected = RecoveryTransportPolicy.selectForRecoveryAttempt(null, null, systemTcp)
        assertEquals(RecoveryTransport.SYSTEM_ADB_TCP, selected.kind)
    }


    @Test
    fun recoveryAttemptUsesDynamicLocalWirelessAdbWhenDiscoveryIsOtherwiseEmpty() {
        val dynamicLocal = TransportCandidate(
            kind = RecoveryTransport.DYNAMIC_LOCAL_WIRELESS_ADB,
            host = "127.0.0.1",
            port = 39921,
            socketReachable = true
        )
        val selected = RecoveryTransportPolicy.selectForRecoveryAttempt(
            persistent = null,
            mdns = null,
            systemTcp = null,
            dynamicLocal = dynamicLocal
        )
        assertEquals(RecoveryTransport.DYNAMIC_LOCAL_WIRELESS_ADB, selected.kind)
        assertEquals("127.0.0.1:39921", selected.endpoint)
        assertTrue(selected.usableForRestart)
    }

    @Test
    fun recoveryAttemptWaitsWhenNothingIsUsable() {
        val selected = RecoveryTransportPolicy.selectForRecoveryAttempt(
            persistent.copy(socketReachable = false),
            null,
            systemTcp.copy(socketReachable = false)
        )
        assertEquals(RecoveryTransport.NONE, selected.kind)
    }
}
