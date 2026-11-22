package moe.shizuku.manager.adb

import java.io.ByteArrayOutputStream
import java.io.DataInputStream
import java.io.DataOutputStream
import java.net.ConnectException
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.SocketTimeoutException
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.security.KeyPairGenerator
import java.security.PrivateKey
import java.security.SecureRandom
import java.security.Signature
import java.security.cert.CertificateFactory
import java.security.cert.X509Certificate
import java.security.spec.RSAKeyGenParameterSpec
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import javax.net.ssl.SSLContext
import javax.net.ssl.SSLEngine
import javax.net.ssl.SSLException
import javax.net.ssl.SSLSocket
import javax.net.ssl.X509ExtendedKeyManager
import javax.net.ssl.X509ExtendedTrustManager
import moe.shizuku.manager.shizuku.AdbFailureClassifier
import moe.shizuku.manager.shizuku.NightDogRecovery
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbRecoveryProtocolTest {

    private val localHosts = setOf("127.0.0.1", "192.168.1.20", "::1", "fe80::20%wlan0")

    // --- AdbEndpointPolicy ---

    @Test
    fun tlsDependeDelTransporteNoDeLaVersionAndroid() {
        assertTrue(AdbEndpoint("192.168.1.20", 37123).requireTls)
        assertFalse(AdbEndpoint("127.0.0.1", 5555, requireTls = false).requireTls)
    }

    @Test
    fun aceptaPuertosDinamicosLocalesYRechazaRemotosOInvalidos() {
        for (port in listOf(1, 5555, 32768, 37123, 45678, 65535)) {
            assertTrue("Puerto local $port", AdbEndpointPolicy.isLocal("192.168.1.20", port, localHosts))
        }
        for (port in listOf(Int.MIN_VALUE, -1, 0, 65536, Int.MAX_VALUE)) {
            assertFalse("Puerto inválido $port", AdbEndpointPolicy.isLocal("127.0.0.1", port, localHosts))
        }
        for (host in listOf("192.168.1.21", "8.8.8.8", "fe80::21%wlan0", "fe80::20%wlan1", "fe80::20", "adb.example.com", "")) {
            assertFalse(host, AdbEndpointPolicy.isLocal(host, 37123, localHosts))
        }
        assertFalse(AdbEndpointPolicy.isLocal("127.0.0.1", 37123, emptySet()))
    }

    @Test
    fun noConservaInterfazDeUnaRedAnterior() {
        assertTrue(AdbEndpointPolicy.isLocal("192.168.1.20", 37123, localHosts))
        val newNetwork = setOf("127.0.0.1", "192.168.2.30")
        assertFalse(AdbEndpointPolicy.isLocal("192.168.1.20", 37123, newNetwork))
        assertTrue(AdbEndpointPolicy.isLocal("192.168.2.30", 45678, newNetwork))
    }

    @Test
    fun seleccionaIpv4LocalEntreMultiplesCandidatosAndroid16YDescartaRemotos() {
        val selected = AdbEndpointPolicy.selectLocalHost(
            candidates = listOf("192.168.1.99", "fe80::20%wlan0", "192.168.1.20"),
            port = 41234,
            localHosts = localHosts
        )
        assertEquals("192.168.1.20", selected)

        val ipv6Only = AdbEndpointPolicy.selectLocalHost(
            candidates = listOf("192.168.1.99", "fe80::20%wlan0"),
            port = 41234,
            localHosts = localHosts
        )
        assertEquals("fe80::20%wlan0", ipv6Only)

        assertEquals(
            null,
            AdbEndpointPolicy.selectLocalHost(
                candidates = listOf("192.168.1.99", "10.0.0.5"),
                port = 41234,
                localHosts = localHosts
            )
        )
        assertEquals(
            null,
            AdbEndpointPolicy.selectLocalHost(
                candidates = listOf("192.168.1.20"),
                port = 0,
                localHosts = localHosts
            )
        )
    }

    // --- AdbMdns backoff ---

    @Test
    fun mdnsReintentosUsanBackoffExponencialAcotado() {
        val expected = listOf(2_000L, 4_000L, 8_000L, 16_000L, 30_000L, 30_000L)
        expected.forEachIndexed { attempt, delay ->
            assertEquals("Intento $attempt", delay, AdbMdns.restartDelayMs(attempt))
        }
        assertEquals(2_000L, AdbMdns.restartDelayMs(-1))
        assertEquals(2_000L, AdbMdns.restartDelayMs(Int.MIN_VALUE))
        assertEquals(30_000L, AdbMdns.restartDelayMs(Int.MAX_VALUE))
    }

    // --- AdbMessage ---

    @Test
    fun adbMessageSerializaYValidaCabeceraYPayload() {
        val payload = byteArrayOf(0, 1, 127, -128, -1)
        val message = AdbMessage(AdbProtocol.A_WRTE, 7, 19, payload)
        val bytes = message.toByteArray()
        assertEquals(24 + payload.size, bytes.size)
        val header = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
        assertEquals(AdbProtocol.A_WRTE, header.int)
        assertEquals(7, header.int)
        assertEquals(19, header.int)
        assertEquals(payload.size, header.int)
        assertEquals(511, header.int)
        assertEquals(AdbProtocol.A_WRTE.inv(), header.int)
        assertArrayEquals(payload, bytes.copyOfRange(24, bytes.size))
        assertTrue(message.validate())

        val badLen = AdbMessage(AdbProtocol.A_WRTE, 19, 1, -1, 0, AdbProtocol.A_WRTE.inv(), null)
        assertFalse(badLen.validate())
        assertThrows(IllegalArgumentException::class.java) { badLen.validateOrThrow() }

        val mismatch = AdbMessage(AdbProtocol.A_WRTE, 19, 1, 3, 3, AdbProtocol.A_WRTE.inv(), byteArrayOf(1, 2))
        assertFalse(mismatch.validate())
        assertThrows(IllegalArgumentException::class.java) { mismatch.validateOrThrow() }
    }

    @Test
    fun adbMessageNoExponePayloadEnDiagnosticosNiErrores() {
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
        val bad = AdbMessage(AdbProtocol.A_WRTE, 19, 1, payload.size, 0, 0, payload)
        val error = assertThrows(IllegalArgumentException::class.java) { bad.validateOrThrow() }
        assertFalse(error.message.orEmpty().contains(secret))
        assertFalse(error.message.orEmpty().contains("data="))
    }

    // --- AdbClient real loopback protocol tests ---

    @Test
    fun adbClientAutenticaConFirmaYVerificaShellYEjecutaComando() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            assertEquals(AdbProtocol.A_CNXN, readAdbMessage(input).command)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, byteArrayOf(1, 2, 3, 4)))
            val sig = readAdbMessage(input)
            assertEquals(AdbProtocol.A_AUTH, sig.command)
            assertEquals(AdbProtocol.ADB_AUTH_SIGNATURE, sig.arg0)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))

            // First command: verifyShell()
            val probeOpen = readAdbMessage(input)
            assertEquals(AdbProtocol.A_OPEN, probeOpen.command)
            assertTrue(probeOpen.data!!.toString(Charsets.UTF_8).contains(AdbClient.SHELL_PROBE_MARKER))
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_OKAY, 10, probeOpen.arg0, null as ByteArray?))
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_WRTE, 10, probeOpen.arg0, "${AdbClient.SHELL_PROBE_MARKER}\n".toByteArray()))
            assertEquals(AdbProtocol.A_OKAY, readAdbMessage(input).command)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CLSE, 10, probeOpen.arg0, null as ByteArray?))
            assertEquals(AdbProtocol.A_CLSE, readAdbMessage(input).command)

            // Second command: actual shell command uses a fresh localId
            val cmdOpen = readAdbMessage(input)
            assertEquals(AdbProtocol.A_OPEN, cmdOpen.command)
            assertTrue(cmdOpen.arg0 != probeOpen.arg0)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_OKAY, 11, cmdOpen.arg0, null as ByteArray?))
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_WRTE, 11, cmdOpen.arg0, "started".toByteArray()))
            assertEquals(AdbProtocol.A_OKAY, readAdbMessage(input).command)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CLSE, 11, cmdOpen.arg0, null as ByteArray?))
            assertEquals(AdbProtocol.A_CLSE, readAdbMessage(input).command)
        }.use { port ->
            val captured = StringBuilder()
            AdbClient(
                host = "127.0.0.1",
                port = port,
                credentials = creds,
                requireTls = false,
                allowPublicKeyPrompt = false
            ).use { client ->
                client.connect()
                assertTrue(client.verifyShell())
                client.shellCommand("echo hi") { captured.append(String(it)) }
            }
            assertEquals("started", captured.toString())
        }
    }

    @Test
    fun adbClientSinPromptNoEnviaClavePublicaCuandoFirmaEsRechazada() {
        val creds = FakeAdbCredentials()
        val publicKeySent = AtomicBoolean(false)
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            assertEquals(AdbProtocol.A_CNXN, readAdbMessage(input).command)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, byteArrayOf(9, 8, 7)))
            val sig = readAdbMessage(input)
            assertEquals(AdbProtocol.ADB_AUTH_SIGNATURE, sig.arg0)
            // Reject signature by sending another token
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_AUTH, AdbProtocol.ADB_AUTH_TOKEN, 0, byteArrayOf(6, 5, 4)))
            runCatching {
                val next = readAdbMessage(input)
                if (next.command == AdbProtocol.A_AUTH && next.arg0 == AdbProtocol.ADB_AUTH_RSAPUBLICKEY) {
                    publicKeySent.set(true)
                }
            }
        }.use { port ->
            val error = assertThrows(AdbAuthorizationException::class.java) {
                AdbClient(
                    host = "127.0.0.1",
                    port = port,
                    credentials = creds,
                    requireTls = false,
                    allowPublicKeyPrompt = false
                ).use { it.connect() }
            }
            assertFalse("No debe spamear ADB_AUTH_RSAPUBLICKEY en modo headless", publicKeySent.get())
            assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(error))
        }
    }

    @Test
    fun adbClientExigeTlsCuandoRequireTlsEsVerdadero() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            readAdbMessage(input)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
        }.use { port ->
            val error = assertThrows(AdbAuthorizationException::class.java) {
                AdbClient(
                    host = "127.0.0.1",
                    port = port,
                    credentials = creds,
                    requireTls = true,
                    allowPublicKeyPrompt = false,
                    tlsSupported = true
                ).use { it.connect() }
            }
            assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(error))
        }
    }

    @Test
    fun adbClientCompletaTlsRealYVerificaShell() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val plainIn = DataInputStream(socket.getInputStream())
            val plainOut = DataOutputStream(socket.getOutputStream())
            assertEquals(AdbProtocol.A_CNXN, readAdbMessage(plainIn).command)
            writeAdbMessage(plainOut, AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null as ByteArray?))
            assertEquals(AdbProtocol.A_STLS, readAdbMessage(plainIn).command)

            val tls = creds.sslContext.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
            tls.useClientMode = false
            tls.needClientAuth = true
            tls.startHandshake()

            val tlsIn = DataInputStream(tls.inputStream)
            val tlsOut = DataOutputStream(tls.outputStream)
            writeAdbMessage(tlsOut, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))

            val open = readAdbMessage(tlsIn)
            assertEquals(AdbProtocol.A_OPEN, open.command)
            writeAdbMessage(tlsOut, AdbMessage(AdbProtocol.A_OKAY, 20, open.arg0, null as ByteArray?))
            writeAdbMessage(tlsOut, AdbMessage(AdbProtocol.A_WRTE, 20, open.arg0, "${AdbClient.SHELL_PROBE_MARKER}\n".toByteArray()))
            assertEquals(AdbProtocol.A_OKAY, readAdbMessage(tlsIn).command)
            writeAdbMessage(tlsOut, AdbMessage(AdbProtocol.A_CLSE, 20, open.arg0, null as ByteArray?))
            assertEquals(AdbProtocol.A_CLSE, readAdbMessage(tlsIn).command)
        }.use { port ->
            AdbClient(
                host = "127.0.0.1",
                port = port,
                credentials = creds,
                requireTls = true,
                allowPublicKeyPrompt = false,
                tlsSupported = true
            ).use { client ->
                client.connect()
                assertTrue(client.verifyShell())
            }
        }
    }

    @Test
    fun adbClientClasificaCierrePostTlsComoFalloDeAutorizacion() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val plainIn = DataInputStream(socket.getInputStream())
            val plainOut = DataOutputStream(socket.getOutputStream())
            readAdbMessage(plainIn)
            writeAdbMessage(plainOut, AdbMessage(AdbProtocol.A_STLS, AdbProtocol.A_STLS_VERSION, 0, null as ByteArray?))
            readAdbMessage(plainIn)

            val tls = creds.sslContext.socketFactory.createSocket(socket, null, socket.port, true) as SSLSocket
            tls.useClientMode = false
            tls.needClientAuth = true
            tls.startHandshake()
            // Simulate adbd rejecting unpaired client cert immediately after TLS handshake
            tls.close()
        }.use { port ->
            val error = assertThrows(Exception::class.java) {
                AdbClient(
                    host = "127.0.0.1",
                    port = port,
                    credentials = creds,
                    requireTls = true,
                    allowPublicKeyPrompt = false,
                    tlsSupported = true
                ).use { it.connect() }
            }
            assertTrue(error is AdbAuthorizationException || error is SSLException)
            assertEquals(NightDogRecovery.FailureCategory.AUTH_FAILED, AdbFailureClassifier.classify(error))
        }
    }

    @Test
    fun adbClientDetectaShellRechazadoConA_CLSE() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            readAdbMessage(input)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "device::"))
            val open1 = readAdbMessage(input)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CLSE, 0, open1.arg0, null as ByteArray?))
            readAdbMessage(input)
            val open2 = readAdbMessage(input)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CLSE, 0, open2.arg0, null as ByteArray?))
            readAdbMessage(input)
        }.use { port ->
            AdbClient("127.0.0.1", port, creds, requireTls = false, allowPublicKeyPrompt = false).use { client ->
                client.connect()
                assertFalse(client.verifyShell())
                val error = assertThrows(AdbShellUnavailableException::class.java) {
                    client.shellCommand("id", null)
                }
                assertEquals(NightDogRecovery.FailureCategory.SHELL_UNAVAILABLE, AdbFailureClassifier.classify(error))
            }
        }
    }

    @Test
    fun adbClientDistingueOfflineDeAutorizacionYRespetaTimeoutYClose() {
        val creds = FakeAdbCredentials()

        // 1. Offline banner
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            readAdbMessage(input)
            writeAdbMessage(output, AdbMessage(AdbProtocol.A_CNXN, AdbProtocol.A_VERSION, AdbProtocol.A_MAXDATA, "offline::"))
        }.use { port ->
            val error = assertThrows(AdbOfflineException::class.java) {
                AdbClient("127.0.0.1", port, creds).use { it.connect() }
            }
            assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(error))
        }

        // 2. Connection refused -> OFFLINE
        val closedPort = ServerSocket(0, 1, InetAddress.getLoopbackAddress()).use { it.localPort }
        val connError = assertThrows(ConnectException::class.java) {
            AdbClient("127.0.0.1", closedPort, creds, connectTimeoutMs = 1_000, readTimeoutMs = 1_000).use { it.connect() }
        }
        assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(connError))

        // 3. Stalled server -> SocketTimeoutException -> OFFLINE
        val holdLatch = CountDownLatch(1)
        withFakeAdbServer { _ ->
            holdLatch.await(3, TimeUnit.SECONDS)
        }.use { port ->
            try {
                val timeoutError = assertThrows(SocketTimeoutException::class.java) {
                    AdbClient("127.0.0.1", port, creds, connectTimeoutMs = 1_000, readTimeoutMs = 200).use { it.connect() }
                }
                assertEquals(NightDogRecovery.FailureCategory.OFFLINE, AdbFailureClassifier.classify(timeoutError))
            } finally {
                holdLatch.countDown()
            }
        }

        // 4. Concurrent close() unblocks blocked read immediately
        val accepted = CountDownLatch(1)
        val releaseServer = CountDownLatch(1)
        withFakeAdbServer { _ ->
            accepted.countDown()
            releaseServer.await(5, TimeUnit.SECONDS)
        }.use { port ->
            val client = AdbClient("127.0.0.1", port, creds, connectTimeoutMs = 2_000, readTimeoutMs = 10_000)
            val failure = AtomicReference<Throwable?>()
            val finished = CountDownLatch(1)
            val worker = Thread {
                try {
                    client.connect()
                } catch (t: Throwable) {
                    failure.set(t)
                } finally {
                    finished.countDown()
                }
            }
            worker.start()
            assertTrue(accepted.await(2, TimeUnit.SECONDS))
            client.close()
            assertTrue("close() debe desbloquear lectura en curso", finished.await(2, TimeUnit.SECONDS))
            assertTrue(failure.get() != null)
            releaseServer.countDown()
        }
    }

    @Test
    fun adbClientRechazaLongitudDeMensajeExcesivaAntesDeAsignarMemoria() {
        val creds = FakeAdbCredentials()
        withFakeAdbServer { socket ->
            val input = DataInputStream(socket.getInputStream())
            val output = DataOutputStream(socket.getOutputStream())
            readAdbMessage(input)
            val header = ByteBuffer.allocate(24).order(ByteOrder.LITTLE_ENDIAN)
                .putInt(AdbProtocol.A_CNXN)
                .putInt(0)
                .putInt(0)
                .putInt(5 * 1024 * 1024) // > 4 MiB limit
                .putInt(0)
                .putInt(AdbProtocol.A_CNXN.inv())
                .array()
            output.write(header)
            output.flush()
        }.use { port ->
            assertThrows(IllegalArgumentException::class.java) {
                AdbClient("127.0.0.1", port, creds).use { it.connect() }
            }
        }
    }

    private class FakeAdbServer(
        private val serverSocket: ServerSocket,
        private val thread: Thread
    ) : AutoCloseable {
        val port: Int = serverSocket.localPort
        override fun close() {
            runCatching { serverSocket.close() }
            thread.join(2_000)
        }
    }

    private fun withFakeAdbServer(handler: (Socket) -> Unit): FakeAdbServerHandle {
        val server = ServerSocket(0, 1, InetAddress.getLoopbackAddress())
        val thread = Thread {
            runCatching {
                server.accept().use { client ->
                    client.soTimeout = 3_000
                    handler(client)
                }
            }
        }
        thread.isDaemon = true
        thread.start()
        return FakeAdbServerHandle(FakeAdbServer(server, thread))
    }

    private class FakeAdbServerHandle(private val inner: FakeAdbServer) {
        fun use(block: (Int) -> Unit) {
            inner.use { block(it.port) }
        }
    }

    private fun readAdbMessage(input: DataInputStream): AdbMessage {
        val raw = ByteArray(AdbMessage.HEADER_LENGTH)
        input.readFully(raw)
        val buffer = ByteBuffer.wrap(raw).order(ByteOrder.LITTLE_ENDIAN)
        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val length = buffer.int
        val crc = buffer.int
        val magic = buffer.int
        val data = if (length > 0) ByteArray(length).also { input.readFully(it) } else null
        return AdbMessage(command, arg0, arg1, length, crc, magic, data).also { it.validateOrThrow() }
    }

    private fun writeAdbMessage(output: DataOutputStream, message: AdbMessage) {
        output.write(message.toByteArray())
        output.flush()
    }

    private class FakeAdbCredentials : AdbCredentials {
        override val adbPublicKey: ByteArray = "fake-public-key\u0000".toByteArray()

        override fun sign(data: ByteArray?): ByteArray = byteArrayOf(0x42, 0x24)

        override val sslContext: SSLContext = sharedSslContext

        companion object {
            private val keyPair = KeyPairGenerator.getInstance("RSA").apply {
                initialize(RSAKeyGenParameterSpec(2048, RSAKeyGenParameterSpec.F4))
            }.generateKeyPair()

            private val certificate: X509Certificate = buildSelfSignedCert(keyPair.public.encoded, keyPair.private)

            private val sharedSslContext: SSLContext = SSLContext.getInstance("TLSv1.3").apply {
                val km = object : X509ExtendedKeyManager() {
                    override fun chooseClientAlias(keyTypes: Array<out String>?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "test"
                    override fun chooseServerAlias(keyType: String?, issuers: Array<out java.security.Principal>?, socket: Socket?) = "test"
                    override fun getCertificateChain(alias: String?) = arrayOf(certificate)
                    override fun getPrivateKey(alias: String?): PrivateKey = keyPair.private
                    override fun getClientAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("test")
                    override fun getServerAliases(keyType: String?, issuers: Array<out java.security.Principal>?) = arrayOf("test")
                }
                val tm = object : X509ExtendedTrustManager() {
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                    override fun checkClientTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, socket: Socket?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?, engine: SSLEngine?) {}
                    override fun checkServerTrusted(chain: Array<out X509Certificate>?, authType: String?) {}
                    override fun getAcceptedIssuers(): Array<X509Certificate> = emptyArray()
                }
                init(arrayOf(km), arrayOf(tm), SecureRandom())
            }

            private fun der(tag: Int, content: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                out.write(tag)
                val len = content.size
                when {
                    len < 0x80 -> out.write(len)
                    len < 0x100 -> {
                        out.write(0x81)
                        out.write(len)
                    }
                    else -> {
                        out.write(0x82)
                        out.write((len shr 8) and 0xFF)
                        out.write(len and 0xFF)
                    }
                }
                out.write(content)
                return out.toByteArray()
            }

            private fun concat(vararg parts: ByteArray): ByteArray {
                val out = ByteArrayOutputStream()
                for (p in parts) out.write(p)
                return out.toByteArray()
            }

            private fun buildSelfSignedCert(spkiDer: ByteArray, privateKey: PrivateKey): X509Certificate {
                // sha256WithRSAEncryption (1.2.840.113549.1.1.11) + NULL
                val sigAlg = der(
                    0x30,
                    concat(
                        byteArrayOf(0x06, 0x09, 0x2A, 0x86.toByte(), 0x48, 0x86.toByte(), 0xF7.toByte(), 0x0D, 0x01, 0x01, 0x0B),
                        byteArrayOf(0x05, 0x00)
                    )
                )
                val cnOid = byteArrayOf(0x06, 0x03, 0x55, 0x04, 0x03)
                val cnVal = der(0x0C, "NightzukuTest".toByteArray(Charsets.UTF_8))
                val name = der(0x30, der(0x31, der(0x30, concat(cnOid, cnVal))))
                val validity = der(
                    0x30,
                    concat(
                        der(0x17, "200101000000Z".toByteArray(Charsets.US_ASCII)),
                        der(0x17, "350101000000Z".toByteArray(Charsets.US_ASCII))
                    )
                )
                val tbsCert = der(
                    0x30,
                    concat(
                        byteArrayOf(0xA0.toByte(), 0x03, 0x02, 0x01, 0x02), // version v3
                        byteArrayOf(0x02, 0x01, 0x01), // serialNumber = 1
                        sigAlg,
                        name,
                        validity,
                        name,
                        spkiDer
                    )
                )
                val sigBytes = Signature.getInstance("SHA256withRSA").apply {
                    initSign(privateKey)
                    update(tbsCert)
                }.sign()
                val bitString = der(0x03, concat(byteArrayOf(0x00), sigBytes))
                val certDer = der(0x30, concat(tbsCert, sigAlg, bitString))
                return CertificateFactory.getInstance("X.509")
                    .generateCertificate(certDer.inputStream()) as X509Certificate
            }
        }
    }
}
