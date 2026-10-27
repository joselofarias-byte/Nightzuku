package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TcpHealthClassifierTest {

    @Test
    fun disabledKeepsConfiguredEndpoint() {
        val health = TcpHealthClassifier.classify(false, "127.0.0.1", 5555, null, null)
        assertEquals(TcpHealthState.DISABLED, health.state)
        assertEquals(TcpCapability.CONFIGURED, health.capability)
        assertEquals("127.0.0.1:5555", health.endpoint)
        assertFalse(health.usableForRestart)
    }

    @Test
    fun enabledUnknownWithoutProbe() {
        val health = TcpHealthClassifier.classify(true, "127.0.0.1", 5555, null, null)
        assertEquals(TcpHealthState.UNKNOWN, health.state)
        assertEquals(TcpCapability.CONFIGURED, health.capability)
        assertFalse(health.usableForRestart)
    }

    @Test
    fun enabledUnreachableIsNotUsable() {
        val health = TcpHealthClassifier.classify(true, "127.0.0.1", 5555, false, false)
        assertEquals(TcpHealthState.ENABLED_UNREACHABLE, health.state)
        assertEquals(TcpCapability.CONFIGURED, health.capability)
        assertFalse(health.usableForRestart)
    }

    @Test
    fun socketReachableIsNotRecoveryAvailable() {
        val health = TcpHealthClassifier.classify(true, "127.0.0.1", 5555, true, false)
        assertEquals(TcpHealthState.ENABLED_REACHABLE, health.state)
        assertEquals(TcpCapability.SOCKET_REACHABLE, health.capability)
        assertFalse(health.usableForRestart)
    }

    @Test
    fun authenticatedIsUsableForRestart() {
        val health = TcpHealthClassifier.classify(true, "127.0.0.1", 5555, true, true)
        assertEquals(TcpHealthState.ENABLED_REACHABLE, health.state)
        assertEquals(TcpCapability.USABLE_FOR_RESTART, health.capability)
        assertTrue(health.usableForRestart)
    }

    @Test
    fun invalidPortIsUnknown() {
        val health = TcpHealthClassifier.classify(true, "127.0.0.1", 0, true, true)
        assertEquals(TcpHealthState.UNKNOWN, health.state)
        assertEquals(TcpCapability.NOT_CONFIGURED, health.capability)
    }
}
