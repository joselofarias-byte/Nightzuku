package moe.shizuku.manager.adb

import java.nio.ByteBuffer
import java.nio.ByteOrder
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbMessageTest {
    @Test
    fun serializaCabeceraLittleEndianYPayloadSinAlterarlo() {
        val payload = byteArrayOf(0, 1, 127, -128, -1)
        val message = AdbMessage(AdbProtocol.A_WRTE, 7, 19, payload)
        val bytes = message.toByteArray()
        assertEquals(24 + payload.size, bytes.size)
        assertArrayEquals(byteArrayOf(0x57, 0x52, 0x54, 0x45), bytes.copyOfRange(0, 4))
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_WRTE, header.int)
        assertEquals(7, header.int)
        assertEquals(19, header.int)
        assertEquals(payload.size, header.int)
        assertEquals(511, header.int)
        assertEquals(AdbProtocol.A_WRTE.inv(), header.int)
        assertArrayEquals(payload, bytes.copyOfRange(24, bytes.size))
        assertTrue(message.validate())
    }

    @Test
    fun terminaElServicioUtf8ConUnSoloByteNulo() {
        val service = "shell:echo conexión"
        val message = AdbMessage(AdbProtocol.A_OPEN, 1, 0, service)
        val expected = (service + '\u0000').toByteArray(Charsets.UTF_8)
        assertArrayEquals(expected, message.data)
        assertEquals(expected.size, message.data_length)
        assertTrue(message.validate())
    }

    @Test
    fun mensajeSinPayloadTieneSoloCabecera() {
        val message = AdbMessage(AdbProtocol.A_OKAY, 1, 19, null)
        assertEquals(24, message.toByteArray().size)
        assertEquals(0, message.data_length)
        assertEquals(0, message.data_crc32)
        assertTrue(message.validate())
    }

    @Test
    fun payloadVacioEsValido() {
        val message = AdbMessage(AdbProtocol.A_CLSE, 1, 19, byteArrayOf())
        assertEquals(24, message.toByteArray().size)
        assertTrue(message.validate())
    }

    @Test
    fun rechazaMagicCorrupto() {
        val message = AdbMessage(AdbProtocol.A_CNXN, 0, 0, 0, 0, 0, null)
        assertFalse(message.validate())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
    }

    @Test
    fun rechazaChecksumCorrupto() {
        val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, 1, 2, AdbProtocol.A_WRTE.inv(), byteArrayOf(1))
        assertFalse(message.validate())
        org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
    }

    @Test
    fun detectaAlteracionDelPayloadTrasCrearElMensaje() {
        val payload = byteArrayOf(1, 2, 3)
        val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, payload)
        payload[0] = 4
        assertFalse(message.validate())
    }

    @Test
    fun rechazaLongitudNegativaAunqueElChecksumSeaCorrecto() {
        for (length in listOf(-1, Int.MIN_VALUE)) {
            val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, length, 0, AdbProtocol.A_WRTE.inv(), null)
            assertFalse(message.validate())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
        }
    }

    @Test
    fun rechazaLongitudDistintaDelPayloadAunqueElChecksumSeaCorrecto() {
        for (length in listOf(0, 1, 3, Int.MAX_VALUE)) {
            val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, length, 3, AdbProtocol.A_WRTE.inv(), byteArrayOf(1, 2))
            assertFalse("Longitud declarada $length", message.validate())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
        }
    }

    @Test
    fun rechazaPayloadAusenteAunqueElChecksumSeaCero() {
        for (payload in listOf<ByteArray?>(null, byteArrayOf())) {
            val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, 1, 0, AdbProtocol.A_WRTE.inv(), payload)
            assertFalse(message.validate())
            org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
        }
    }

    @Test
    fun diagnosticoNoExponePayloadDeAutenticacionNiShell() {
        val secret = "token-privado-regresion"
        val payload = secret.toByteArray(Charsets.UTF_8)
        for (command in listOf(AdbProtocol.A_AUTH, AdbProtocol.A_OPEN, AdbProtocol.A_WRTE)) {
            val message = AdbMessage(command, 19, 1, payload)
            for (diagnostic in listOf(message.toStringShort(), message.toString())) {
                assertFalse(diagnostic.contains(secret))
                assertFalse(diagnostic.contains(payload.contentToString()))
                assertFalse(diagnostic.contains("data="))
                assertTrue(diagnostic.contains("data_length=${payload.size}"))
            }
        }
    }

    @Test
    fun errorDeValidacionTampocoExponePayload() {
        val secret = "salida-shell-privada"
        val payload = secret.toByteArray(Charsets.UTF_8)
        val message = AdbMessage(AdbProtocol.A_WRTE, 19, 1, payload.size, 0, 0, payload)
        val error = org.junit.Assert.assertThrows(IllegalArgumentException::class.java) { message.validateOrThrow() }
        val diagnostic = error.message.orEmpty()
        assertTrue(diagnostic.contains("bad message"))
        assertFalse(diagnostic.contains(secret))
        assertFalse(diagnostic.contains(payload.contentToString()))
        assertFalse(diagnostic.contains("data="))
    }

    @Test
    fun igualdadYHashComparanElContenidoDelPayload() {
        val first = AdbMessage(AdbProtocol.A_WRTE, 19, 1, byteArrayOf(1, 2))
        val second = AdbMessage(AdbProtocol.A_WRTE, 19, 1, byteArrayOf(1, 2))
        assertEquals(first, second)
        assertEquals(first.hashCode(), second.hashCode())
        assertFalse(first == AdbMessage(AdbProtocol.A_WRTE, 19, 1, byteArrayOf(1, 3)))
        assertFalse(first == AdbMessage(AdbProtocol.A_WRTE, 20, 1, byteArrayOf(1, 2)))
    }
}
