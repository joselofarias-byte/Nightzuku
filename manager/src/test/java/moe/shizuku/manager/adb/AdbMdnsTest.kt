package moe.shizuku.manager.adb

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMdnsTest {
    @Test
    fun reintentosUsanBackoffExponencialConLimite() {
        val expected = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expected.forEachIndexed { attempt, delay ->
            assertEquals("Intento $attempt", delay, AdbMdns.restartDelayMs(attempt))
        }
    }

    @Test
    fun contadorNegativoNoCausaReintentosInmediatos() {
        assertEquals(2_000L, AdbMdns.restartDelayMs(-1))
        assertEquals(2_000L, AdbMdns.restartDelayMs(Int.MIN_VALUE))
    }

    @Test
    fun contadorExtremoNoDesbordaElTiempoDeEspera() {
        assertEquals(30_000L, AdbMdns.restartDelayMs(Int.MAX_VALUE))
        for (attempt in 0..100) {
            assertTrue(AdbMdns.restartDelayMs(attempt) in 2_000L..30_000L)
        }
    }

    @Test
    fun nuevaSesionReiniciaElTiempoInicial() {
        assertEquals(30_000L, AdbMdns.restartDelayMs(100))
        assertEquals(2_000L, AdbMdns.restartDelayMs(0))
    }
}
