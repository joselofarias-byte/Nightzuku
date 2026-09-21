package moe.shizuku.manager.adb

import moe.shizuku.manager.ShizukuSettings
import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_MAXDATA
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import moe.shizuku.manager.adb.AdbProtocol.A_VERSION
import java.io.DataInputStream
import java.net.InetSocketAddress
import java.net.Socket
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger

/**
 * Last-resort discovery for Android Wireless debugging when mDNS and readable
 * ADB port properties are unavailable.
 *
 * This is intentionally loopback-only. It never scans the LAN. The probe sends
 * an ADB CNXN header and accepts only endpoints that answer with a valid ADB
 * CNXN/AUTH/STLS header, so an arbitrary open localhost port is not selected.
 */
object AdbLocalWirelessDiscovery {

    private const val LOOPBACK_HOST = "127.0.0.1"
    private const val PREF_LAST_DYNAMIC_PORT = "nightzuku_last_dynamic_adb_port"
    private const val PORT_RANGE_FILE = "/proc/sys/net/ipv4/ip_local_port_range"
    private const val FALLBACK_LOW = 32768
    private const val FALLBACK_HIGH = 60999
    private const val WORKERS = 24
    private const val CONNECT_TIMEOUT_MS = 35
    private const val READ_TIMEOUT_MS = 300
    private const val TOTAL_SCAN_TIMEOUT_MS = 12_000L

    fun lastKnownEndpoint(): AdbEndpoint? {
        val port = ShizukuSettings.getPreferences().getInt(PREF_LAST_DYNAMIC_PORT, -1)
        return port.takeIf { it in 1..65535 }?.let { AdbEndpoint(LOOPBACK_HOST, it) }
    }

    fun discover(): AdbEndpoint? {
        lastKnownEndpoint()?.let { endpoint ->
            if (looksLikeAdb(endpoint.port)) return endpoint
        }

        val range = localEphemeralRange()
        val nextPort = AtomicInteger(range.first)
        val foundPort = AtomicInteger(-1)
        val done = CountDownLatch(WORKERS)
        val executor = Executors.newFixedThreadPool(WORKERS) { runnable ->
            Thread(runnable, "nightzuku-adb-local-scan").apply { isDaemon = true }
        }

        repeat(WORKERS) {
            executor.execute {
                try {
                    while (foundPort.get() < 0) {
                        val port = nextPort.getAndIncrement()
                        if (port > range.last) break
                        if (looksLikeAdb(port)) {
                            foundPort.compareAndSet(-1, port)
                            break
                        }
                    }
                } finally {
                    done.countDown()
                }
            }
        }

        try {
            done.await(TOTAL_SCAN_TIMEOUT_MS, TimeUnit.MILLISECONDS)
        } finally {
            executor.shutdownNow()
        }

        val port = foundPort.get()
        if (port !in 1..65535) return null

        ShizukuSettings.getPreferences()
            .edit()
            .putInt(PREF_LAST_DYNAMIC_PORT, port)
            .apply()

        return AdbEndpoint(LOOPBACK_HOST, port)
    }

    internal fun localEphemeralRange(): IntRange {
        val parsed = runCatching {
            val values = java.io.File(PORT_RANGE_FILE)
                .readText()
                .trim()
                .split(Regex("\\s+"))
            val low = values.getOrNull(0)?.toIntOrNull()
            val high = values.getOrNull(1)?.toIntOrNull()
            if (low != null && high != null && low in 1024..65535 && high in low..65535) {
                low..high
            } else {
                null
            }
        }.getOrNull()

        return parsed ?: (FALLBACK_LOW..FALLBACK_HIGH)
    }

    internal fun isAdbHeader(command: Int, magic: Int): Boolean {
        val validMagic = magic == (command.toLong() xor 0xFFFFFFFFL).toInt()
        return validMagic && (command == A_CNXN || command == A_AUTH || command == A_STLS)
    }

    private fun looksLikeAdb(port: Int): Boolean {
        if (port !in 1..65535) return false

        return runCatching {
            Socket().use { socket ->
                socket.tcpNoDelay = true
                socket.soTimeout = READ_TIMEOUT_MS
                socket.connect(InetSocketAddress(LOOPBACK_HOST, port), CONNECT_TIMEOUT_MS)

                socket.getOutputStream().apply {
                    write(AdbMessage(A_CNXN, A_VERSION, A_MAXDATA, "host::").toByteArray())
                    flush()
                }

                val header = ByteArray(AdbMessage.HEADER_LENGTH)
                DataInputStream(socket.getInputStream()).readFully(header)
                val buffer = ByteBuffer.wrap(header).order(ByteOrder.LITTLE_ENDIAN)
                val command = buffer.int
                buffer.int // arg0
                buffer.int // arg1
                val dataLength = buffer.int
                buffer.int // checksum
                val magic = buffer.int

                dataLength in 0..(1024 * 1024) && isAdbHeader(command, magic)
            }
        }.getOrDefault(false)
    }
}
