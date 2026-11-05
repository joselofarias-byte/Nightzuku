package moe.shizuku.manager.adb

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbEndpointPolicyTest {
    private val localHosts = setOf("127.0.0.1", "192.168.1.20", "::1", "fe80::20%wlan0")

    @Test
    fun tlsDependeDelTransporteNoDeLaVersionAndroid() {
        assertTrue(AdbEndpoint("192.168.1.20", 37123).requireTls)
        assertFalse(AdbEndpoint("127.0.0.1", 5555, requireTls = false).requireTls)
    }

    @Test
    fun aceptaPuertosDinamicosLocalesSinPuertoPreferido() {
        for (port in listOf(1, 5555, 32768, 37123, 45678, 65535)) {
            assertTrue("Puerto local $port", AdbEndpointPolicy.isLocal("192.168.1.20", port, localHosts))
        }
    }

    @Test
    fun rechazaPuertosFueraDelRangoTcp() {
        for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
            assertFalse("Puerto inválido $port", AdbEndpointPolicy.isLocal("127.0.0.1", port, localHosts))
        }
    }

    @Test
    fun rechazaOtroDispositivoAunqueEsteEnLaMismaRed() {
        for (host in listOf("192.168.1.21", "8.8.8.8", "fe80::21%wlan0", "adb.example.com", "")) {
            assertFalse(host, AdbEndpointPolicy.isLocal(host, 37123, localHosts))
        }
    }

    @Test
    fun aceptaIpv4Ipv6YLoopbackDeLasInterfacesActuales() {
        for (host in localHosts) {
            assertTrue(host, AdbEndpointPolicy.isLocal(host, 37123, localHosts))
        }
    }

    @Test
    fun noConservaLaInterfazDeUnaOperacionAnterior() {
        assertTrue(AdbEndpointPolicy.isLocal("192.168.1.20", 37123, localHosts))
        val newNetwork = setOf("127.0.0.1", "192.168.2.30")
        assertFalse(AdbEndpointPolicy.isLocal("192.168.1.20", 37123, newNetwork))
        assertTrue(AdbEndpointPolicy.isLocal("192.168.2.30", 45678, newNetwork))
    }

    @Test
    fun noUsaUnHostAnteriorSiFallaLaLecturaDeInterfaces() {
        assertTrue(AdbEndpointPolicy.isLocal("127.0.0.1", 37123, localHosts))
        assertFalse(AdbEndpointPolicy.isLocal("127.0.0.1", 37123, emptySet()))
    }

    @Test
    fun noConfundeElAmbitoDeUnaDireccionIpv6() {
        assertFalse(AdbEndpointPolicy.isLocal("fe80::20%wlan1", 37123, localHosts))
        assertFalse(AdbEndpointPolicy.isLocal("fe80::20", 37123, localHosts))
    }
}
