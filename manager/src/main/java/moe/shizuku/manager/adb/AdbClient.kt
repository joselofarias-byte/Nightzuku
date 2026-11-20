package moe.shizuku.manager.adb

import android.util.Log
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_RSAPUBLICKEY
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_SIGNATURE
import moe.shizuku.manager.adb.AdbProtocol.ADB_AUTH_TOKEN
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CLSE
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_OKAY
import moe.shizuku.manager.adb.AdbProtocol.A_OPEN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_STLS_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import moe.shizuku.manager.adb.AdbProtocol.A_WRTE
import rikka.core.util.BuildUtils
import java.io.ByteArrayOutputStream
import java.io.Closeable
import java.io.DataInputStream
import java.io.DataOutputStream
import java.io.EOFException
import java.io.IOException
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import javax.net.ssl.SSLSocket

private const val TAG = "AdbClient"

class AdbClient internal constructor(
    private val host: String,
    private val port: Int,
    private val credentials: AdbCredentials,
    private val requireTls: Boolean = false,
    private val allowPublicKeyPrompt: Boolean = true,
    private val connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
    private val readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS,
    private val tlsSupported: Boolean = BuildUtils.atLeast29
) : Closeable {

    constructor(
        host: String,
        port: Int,
        key: AdbKey,
        requireTls: Boolean = false,
        allowPublicKeyPrompt: Boolean = true,
        connectTimeoutMs: Int = DEFAULT_CONNECT_TIMEOUT_MS,
        readTimeoutMs: Int = DEFAULT_READ_TIMEOUT_MS
    ) : this(
        host = host,
        port = port,
        credentials = key as AdbCredentials,
        requireTls = requireTls,
        allowPublicKeyPrompt = allowPublicKeyPrompt,
        connectTimeoutMs = connectTimeoutMs,
        readTimeoutMs = readTimeoutMs
    )

    private val closeLock = Any()
    @Volatile private var closed = false
    private var nextLocalId = 1

    private var socket: Socket? = null
    private var plainInputStream: DataInputStream? = null
    private var plainOutputStream: DataOutputStream? = null

    private var useTls = false

    private var tlsSocket: SSLSocket? = null
    private var tlsInputStream: DataInputStream? = null
    private var tlsOutputStream: DataOutputStream? = null

    private val inputStream: DataInputStream
        get() = (if (useTls) tlsInputStream else plainInputStream)
            ?: throw IOException("ADB client is not connected")

    private val outputStream: DataOutputStream
        get() = (if (useTls) tlsOutputStream else plainOutputStream)
            ?: throw IOException("ADB client is not connected")

    fun connect() {
        val plainSocket = Socket().apply {
            soTimeout = readTimeoutMs
            tcpNoDelay = true
        }
        synchronized(closeLock) {
            if (closed) {
                runCatching { plainSocket.close() }
                throw IOException("ADB client closed")
            }
            socket = plainSocket
        }

        plainSocket.connect(InetSocketAddress(host, port), connectTimeoutMs)
        plainInputStream = DataInputStream(plainSocket.getInputStream())
        plainOutputStream = DataOutputStream(plainSocket.getOutputStream())

        write(A_CNXN, A_VERSION, A_MAXDATA, "host::")

        var message = read()
        if (requireTls && message.command != A_STLS) {
            throw AdbAuthorizationException("Se requiere ADB inalámbrico TLS")
        }
        if (message.command == A_STLS) {
            if (!tlsSupported) {
                error("Connect to adb with TLS is not supported before Android 9")
            }
            write(A_STLS, A_STLS_VERSION, 0)

            val sslContext = credentials.sslContext
            val upgraded = sslContext.socketFactory.createSocket(plainSocket, host, port, true) as SSLSocket
            upgraded.soTimeout = readTimeoutMs
            synchronized(closeLock) {
                if (closed) {
                    runCatching { upgraded.close() }
                    throw IOException("ADB client closed")
                }
                tlsSocket = upgraded
            }
            upgraded.startHandshake()
            Log.d(TAG, "Handshake succeeded.")

            tlsInputStream = DataInputStream(upgraded.inputStream)
            tlsOutputStream = DataOutputStream(upgraded.outputStream)
            useTls = true

            message = try {
                read()
            } catch (e: EOFException) {
                throw AdbAuthorizationException(
                    "Sesión TLS cerrada antes de completar la autenticación ADB",
                    e
                )
            }
        } else if (message.command == A_AUTH) {
            if (message.arg0 != ADB_AUTH_TOKEN) error("not A_AUTH ADB_AUTH_TOKEN")
            write(A_AUTH, ADB_AUTH_SIGNATURE, 0, credentials.sign(message.data))

            message = read()
            if (message.command != A_CNXN) {
                if (!allowPublicKeyPrompt) {
                    throw AdbAuthorizationException("La clave ADB no está autorizada en este endpoint")
                }
                write(A_AUTH, ADB_AUTH_RSAPUBLICKEY, 0, credentials.adbPublicKey)
                message = read()
                if (message.command != A_CNXN) {
                    throw AdbAuthorizationException("Autorización ADB rechazada")
                }
            }
        }

        if (message.command != A_CNXN) error("not A_CNXN")

        val banner = message.data?.toString(Charsets.UTF_8)?.trimEnd('\u0000').orEmpty()
        if (banner.startsWith("unauthorized")) {
            throw AdbAuthorizationException("Dispositivo ADB no autorizado")
        }
        if (banner.startsWith("offline")) {
            throw AdbOfflineException("Dispositivo ADB offline")
        }
    }

    fun verifyShell(): Boolean {
        val output = ByteArrayOutputStream()
        val opened = executeService("shell:echo $SHELL_PROBE_MARKER") { chunk ->
            val remaining = MAX_PROBE_OUTPUT_BYTES - output.size()
            if (remaining > 0) {
                output.write(chunk, 0, minOf(chunk.size, remaining))
            }
        }
        if (!opened) return false
        return output.toString(Charsets.UTF_8.name()).contains(SHELL_PROBE_MARKER)
    }

    fun shellCommand(command: String, listener: ((ByteArray) -> Unit)?) {
        val opened = executeService("shell:$command", listener)
        if (!opened) {
            throw AdbShellUnavailableException("El endpoint ADB rechazó abrir una sesión shell")
        }
    }

    fun serviceCommand(service: String, listener: ((ByteArray) -> Unit)? = null) {
        executeService(service, listener)
    }

    private fun executeService(service: String, listener: ((ByteArray) -> Unit)? = null): Boolean {
        val localId = nextLocalId++
        write(A_OPEN, localId, 0, service)

        var message = read()
        return when (message.command) {
            A_OKAY -> {
                while (true) {
                    message = read()
                    val remoteId = message.arg0
                    when (message.command) {
                        A_WRTE -> {
                            if (message.data_length > 0) {
                                listener?.invoke(message.data!!)
                            }
                            write(A_OKAY, localId, remoteId)
                        }
                        A_CLSE -> {
                            write(A_CLSE, localId, remoteId)
                            break
                        }
                        else -> error("not A_WRTE or A_CLSE")
                    }
                }
                true
            }
            A_CLSE -> {
                val remoteId = message.arg0
                write(A_CLSE, localId, remoteId)
                false
            }
            else -> error("not A_OKAY or A_CLSE")
        }
    }

    private fun write(command: Int, arg0: Int, arg1: Int, data: ByteArray? = null) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(command: Int, arg0: Int, arg1: Int, data: String) = write(AdbMessage(command, arg0, arg1, data))

    private fun write(message: AdbMessage) {
        outputStream.write(message.toByteArray())
        outputStream.flush()
        Log.d(TAG, "write ${message.toStringShort()}")
    }

    private fun read(): AdbMessage {
        val buffer = ByteBuffer.allocate(AdbMessage.HEADER_LENGTH).order(ByteOrder.LITTLE_ENDIAN)

        inputStream.readFully(buffer.array(), 0, AdbMessage.HEADER_LENGTH)

        val command = buffer.int
        val arg0 = buffer.int
        val arg1 = buffer.int
        val dataLength = buffer.int
        require(dataLength in 0..MAX_MESSAGE_DATA_LENGTH) { "Longitud de mensaje ADB inválida" }
        val checksum = buffer.int
        val magic = buffer.int
        val data = if (dataLength > 0) {
            ByteArray(dataLength).also { inputStream.readFully(it) }
        } else {
            null
        }
        val message = AdbMessage(command, arg0, arg1, dataLength, checksum, magic, data)
        message.validateOrThrow()
        Log.d(TAG, "read ${message.toStringShort()}")
        return message
    }

    override fun close() {
        val plainSock: Socket?
        val sslSock: SSLSocket?
        val plainIn: DataInputStream?
        val plainOut: DataOutputStream?
        val sslIn: DataInputStream?
        val sslOut: DataOutputStream?

        synchronized(closeLock) {
            closed = true
            plainSock = socket
            sslSock = tlsSocket
            plainIn = plainInputStream
            plainOut = plainOutputStream
            sslIn = tlsInputStream
            sslOut = tlsOutputStream
            socket = null
            tlsSocket = null
            plainInputStream = null
            plainOutputStream = null
            tlsInputStream = null
            tlsOutputStream = null
        }

        runCatching { sslIn?.close() }
        runCatching { sslOut?.close() }
        runCatching { sslSock?.close() }
        runCatching { plainIn?.close() }
        runCatching { plainOut?.close() }
        runCatching { plainSock?.close() }
    }

    companion object {
        const val DEFAULT_CONNECT_TIMEOUT_MS = 5_000
        const val DEFAULT_READ_TIMEOUT_MS = 10_000
        private const val MAX_MESSAGE_DATA_LENGTH = 4 * 1024 * 1024
        private const val MAX_PROBE_OUTPUT_BYTES = 4096
        internal const val SHELL_PROBE_MARKER = "__NIGHTZUKU_ADB_SHELL_OK__"
    }
}
