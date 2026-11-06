package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NetworkChangePolicyTest {

    @Test
    fun recognizesLoopbackHosts() {
        assertTrue(NetworkChangePolicy.isLoopbackHost("127.0.0.1"))
        assertTrue(NetworkChangePolicy.isLoopbackHost("127.1.2.3"))
        assertTrue(NetworkChangePolicy.isLoopbackHost("localhost"))
        assertTrue(NetworkChangePolicy.isLoopbackHost("::1"))
        assertTrue(NetworkChangePolicy.isLoopbackHost("[::1]"))
        assertFalse(NetworkChangePolicy.isLoopbackHost("192.168.1.20"))
        assertFalse(NetworkChangePolicy.isLoopbackHost("10.0.0.8"))
        assertFalse(NetworkChangePolicy.isLoopbackHost(null))
        assertFalse(NetworkChangePolicy.isLoopbackHost("127.0.0"))
    }

    @Test
    fun dropsCachedLanEndpointsAndKeepsLoopback() {
        assertTrue(NetworkChangePolicy.shouldDropCachedEndpoint("192.168.1.20"))
        assertTrue(NetworkChangePolicy.shouldDropCachedEndpoint(null))
        assertFalse(NetworkChangePolicy.shouldDropCachedEndpoint("127.0.0.1"))
    }

    @Test
    fun parsesEndpointHost() {
        assertEquals("127.0.0.1", NetworkChangePolicy.hostOf("127.0.0.1:5555"))
        assertEquals("192.168.1.20", NetworkChangePolicy.hostOf("192.168.1.20:37000"))
        assertEquals("::1", NetworkChangePolicy.hostOf("[::1]:5555"))
    }

    @Test
    fun steadyStateSkipsLoopbackScanWhenAnyTransportIsReachable() {
        assertFalse(
            NetworkChangePolicy.shouldDiscoverDynamicLocal(
                networkChanged = false,
                persistentReachable = false,
                persistentHost = null,
                mdnsReachable = true,
                mdnsHost = "192.168.1.20",
                systemReachable = false,
                systemHost = null
            )
        )
    }

    @Test
    fun networkChangeStillScansLoopbackWhenOnlyLanIsReachable() {
        assertTrue(
            NetworkChangePolicy.shouldDiscoverDynamicLocal(
                networkChanged = true,
                persistentReachable = true,
                persistentHost = "192.168.1.20",
                mdnsReachable = true,
                mdnsHost = "192.168.1.20",
                systemReachable = false,
                systemHost = null
            )
        )
    }

    @Test
    fun networkChangeSkipsScanWhenLoopbackIsAlreadyReachable() {
        assertFalse(
            NetworkChangePolicy.shouldDiscoverDynamicLocal(
                networkChanged = true,
                persistentReachable = true,
                persistentHost = "127.0.0.1",
                mdnsReachable = false,
                mdnsHost = "192.168.1.20",
                systemReachable = false,
                systemHost = null
            )
        )
    }
}
