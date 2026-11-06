package com.joselofarias.oppoassist.core

import java.net.Inet6Address
import java.net.InetAddress
import java.security.MessageDigest

object AgentInfo {
    const val VERSION = "1.0.0"
    const val PORT = 8765
}

data class LocalNic(
    val name: String,
    val up: Boolean,
    val addresses: List<InetAddress>,
)

object TailscaleAddresses {
    private val ipv6Literal = Regex("^[0-9A-Fa-f:]+$")

    fun isTailscale(address: InetAddress): Boolean {
        val bytes = address.address
        if (address is Inet6Address || bytes.size == 16) {
            if (bytes.size < 6) return false
            return bytes[0] == 0xfd.toByte() &&
                bytes[1] == 0x7a.toByte() &&
                bytes[2] == 0x11.toByte() &&
                bytes[3] == 0x5c.toByte() &&
                bytes[4] == 0xa1.toByte() &&
                bytes[5] == 0xe0.toByte()
        }
        if (bytes.size < 2) return false
        val a = bytes[0].toInt() and 0xff
        val b = bytes[1].toInt() and 0xff
        return a == 100 && b in 64..127
    }

    fun isTailscaleLiteral(host: String): Boolean {
        if (host.contains(':')) return isIpv6Tailscale(host)
        val parts = host.split('.')
        if (parts.size != 4) return false
        val nums = IntArray(4)
        for (index in parts.indices) {
            val part = parts[index]
            if (part.isEmpty() || part.length > 3) return false
            if (part.length > 1 && part.startsWith('0')) return false
            val value = part.toIntOrNull() ?: return false
            if (value !in 0..255) return false
            nums[index] = value
        }
        return nums[0] == 100 && nums[1] in 64..127
    }

    private fun isIpv6Tailscale(host: String): Boolean {
        if (!ipv6Literal.matches(host)) return false
        if (host.count { it == ':' } < 2) return false
        val address = runCatching { InetAddress.getByName(host) }.getOrNull() ?: return false
        return isTailscale(address)
    }
}

object TailscaleBind {
    fun choose(nics: List<LocalNic>): InetAddress? {
        val up = nics.filter { it.up }
        val named = up.filter { it.name.startsWith("tailscale") }
            .flatMap { it.addresses }
            .firstOrNull { it.isUsableTailscale() }
        if (named != null) return named
        return up.flatMap { it.addresses }.firstOrNull { it.isUsableTailscale() }
    }

    private fun InetAddress.isUsableTailscale(): Boolean =
        !isLoopbackAddress && !isAnyLocalAddress && TailscaleAddresses.isTailscale(this)
}

object TokenAuth {
    fun matches(expected: String, presented: String): Boolean {
        val a = expected.toByteArray(Charsets.UTF_8)
        val b = presented.toByteArray(Charsets.UTF_8)
        val padded = if (b.size == a.size) b else ByteArray(a.size)
        val equal = MessageDigest.isEqual(a, padded)
        return b.size == a.size && a.isNotEmpty() && equal
    }
}

fun interface BindPolicy {
    fun allow(address: InetAddress): Boolean

    companion object {
        fun production(): BindPolicy = BindPolicy { TailscaleAddresses.isTailscale(it) }

        fun loopbackForTests(): BindPolicy = BindPolicy { it.isLoopbackAddress }
    }
}
