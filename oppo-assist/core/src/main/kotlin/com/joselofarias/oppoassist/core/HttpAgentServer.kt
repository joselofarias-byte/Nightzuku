package com.joselofarias.oppoassist.core

import java.io.ByteArrayOutputStream
import java.io.InputStream
import java.net.InetAddress
import java.net.InetSocketAddress
import java.net.ServerSocket
import java.net.Socket
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicLong

class HttpAgentServer(
    private val address: InetAddress,
    private val port: Int,
    private val token: String,
    private val policy: BindPolicy,
    private val handler: RpcHandler,
    private val clock: () -> Long = { System.currentTimeMillis() },
) {
    private val guarded = GuardedHandler(handler)
    private val ready = CountDownLatch(1)
    private val windowStart = AtomicLong(0)
    private val windowCount = AtomicInteger(0)

    @Volatile private var server: ServerSocket? = null
    @Volatile private var thread: Thread? = null

    fun start(): Int {
        require(token.length >= 32) { "token corto" }
        if (address.isAnyLocalAddress || !policy.allow(address)) {
            throw IllegalArgumentException("direccion_rechazada")
        }
        val socket = ServerSocket()
        try {
            socket.bind(InetSocketAddress(address, port), 8)
        } catch (error: Exception) {
            socket.close()
            throw error
        }
        val bound = socket.inetAddress
        if (bound.isAnyLocalAddress || !policy.allow(bound)) {
            socket.close()
            throw IllegalStateException("bind_rechazado")
        }
        server = socket
        val worker = Thread({ loop(socket) }, "oppo-assist-http")
        worker.isDaemon = true
        thread = worker
        worker.start()
        if (!ready.await(2, TimeUnit.SECONDS)) {
            socket.close()
            throw IllegalStateException("no_arranco")
        }
        return socket.localPort
    }

    fun stop() {
        try {
            server?.close()
        } catch (_: Exception) {
        }
        thread?.join(1000)
    }

    private fun loop(socket: ServerSocket) {
        ready.countDown()
        while (!socket.isClosed) {
            val client = try {
                socket.accept()
            } catch (_: Exception) {
                break
            }
            try {
                serve(client)
            } catch (_: Exception) {
                // un cliente malformado no tira el servicio
            } finally {
                try {
                    client.close()
                } catch (_: Exception) {
                }
            }
        }
    }

    private fun serve(client: Socket) {
        client.soTimeout = 10_000
        val input = client.getInputStream()
        val header = readHeaders(input, 8192)
            ?: return writeJson(client, 400, "bad_request", errorBody("pedido_invalido"))
        val lines = header.split("\r\n")
        val request = lines.firstOrNull()?.split(" ") ?: return writeJson(client, 400, "bad_request", errorBody("pedido_invalido"))
        if (request.size < 2) return writeJson(client, 400, "bad_request", errorBody("pedido_invalido"))
        var contentLength = -1
        var authorization: String? = null
        for (line in lines.drop(1)) {
            val idx = line.indexOf(':')
            if (idx <= 0) continue
            val name = line.substring(0, idx).trim().lowercase()
            val value = line.substring(idx + 1).trim()
            when (name) {
                "content-length" -> contentLength = value.toIntOrNull() ?: -1
                "authorization" -> authorization = value
            }
        }
        if (request[0] != "POST" || request[1] != "/v1/rpc") {
            return writeJson(client, 404, "not_found", errorBody("no_encontrado"))
        }
        val presented = bearer(authorization)
        if (presented == null || !TokenAuth.matches(token, presented)) {
            return writeJson(client, 401, "unauthorized", errorBody("no_autorizado"))
        }
        if (!allowAuthed(clock())) {
            return writeJson(client, 429, "too_many", errorBody("demasiados_pedidos"))
        }
        if (contentLength !in 2..65_536) {
            return writeJson(client, 400, "bad_request", errorBody("cuerpo_invalido"))
        }
        val body = ByteArray(contentLength)
        var offset = 0
        while (offset < contentLength) {
            val read = input.read(body, offset, contentLength - offset)
            if (read < 0) return writeJson(client, 400, "bad_request", errorBody("cuerpo_incompleto"))
            offset += read
        }
        val parsed = try {
            JsonCodec.parse(body.toString(Charsets.UTF_8))
        } catch (_: JsonException) {
            null
        }
        val fields = parsed?.asObj()
        val op = fields?.get("op")?.asString()
        if (fields == null || op.isNullOrBlank()) {
            return writeJson(client, 400, "bad_request", errorBody("operacion_invalida"))
        }
        val result = try {
            guarded.handle(op, fields)
        } catch (_: Exception) {
            RpcResult(ok = false, error = "error_interno")
        }
        writeJson(client, 200, "ok", JsonCodec.stringify(result.toJson()))
    }

    // ponytail: ventana fija de 10 s y 30 pedidos autenticados.
    // alcanza para un cuidador.
    // si aparece abuso dentro de la tailnet, bajar el cupo.
    private fun allowAuthed(now: Long): Boolean {
        val start = windowStart.get()
        if (now - start > 10_000) {
            windowStart.set(now)
            windowCount.set(1)
            return true
        }
        return windowCount.incrementAndGet() <= 30
    }

    private fun bearer(header: String?): String? {
        if (header == null || !header.startsWith("Bearer ")) return null
        val value = header.removePrefix("Bearer ").trim()
        if (value.isEmpty() || value.any { it.isWhitespace() }) return null
        return value
    }

    private fun readHeaders(input: InputStream, max: Int): String? {
        val buffer = ByteArrayOutputStream()
        while (buffer.size() < max) {
            val byte = input.read()
            if (byte < 0) return null
            buffer.write(byte)
            val raw = buffer.toByteArray()
            val n = raw.size
            if (n >= 4 &&
                raw[n - 4] == 13.toByte() &&
                raw[n - 3] == 10.toByte() &&
                raw[n - 2] == 13.toByte() &&
                raw[n - 1] == 10.toByte()
            ) {
                return String(raw, Charsets.ISO_8859_1)
            }
        }
        return null
    }

    private fun errorBody(code: String): String = JsonCodec.stringify(RpcResult(ok = false, error = code).toJson())

    private fun writeJson(client: Socket, code: Int, reason: String, body: String) {
        val payload = body.toByteArray(Charsets.UTF_8)
        val head = "HTTP/1.1 $code $reason\r\nContent-Type: application/json; charset=utf-8\r\nContent-Length: ${payload.size}\r\nConnection: close\r\n\r\n"
        val output = client.getOutputStream()
        output.write(head.toByteArray(Charsets.US_ASCII))
        output.write(payload)
        output.flush()
    }
}
