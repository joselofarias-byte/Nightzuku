package com.joselofarias.oppoassist.core

import java.io.File

enum class Channel { SMS, RCS }

enum class SmsDecision { FORWARDED, HELD, DROPPED_RCS, DROPPED_DISABLED }

data class SmsMessage(
    val id: String,
    val channel: Channel,
    val from: String,
    val body: String,
    val receivedAtMs: Long,
    val expiresAtMs: Long?,
)

data class IngressResult(val decision: SmsDecision, val id: String?)

object SensitiveSms {
    private val keywords = listOf(
        "codigo", "código", "otp", "2fa", "verificacion", "verificación",
        "verification", "passcode", "one-time", "un solo uso",
        "autenticacion", "autenticación", "security code",
        "código de seguridad", "codigo de seguridad", "clave dinamica",
        "clave dinámica", "token",
    )
    private val code = Regex("(?<!\\d)(\\d{4,8}|\\d{3}[- ]\\d{3})(?!\\d)")

    fun looksLikeOneTimeCode(body: String): Boolean {
        val trimmed = body.trim()
        if (trimmed.matches(Regex("\\d{4,8}"))) return true
        val folded = trimmed.lowercase()
        val hasKeyword = keywords.any { folded.contains(it) }
        return hasKeyword && code.containsMatchIn(trimmed)
    }
}

object SmsPolicy {
    const val CONSENT_TTL_MS = 10 * 60 * 1000L

    fun decide(channel: Channel, body: String, forwardEnabled: Boolean): SmsDecision {
        if (channel == Channel.RCS) return SmsDecision.DROPPED_RCS
        if (SensitiveSms.looksLikeOneTimeCode(body)) return SmsDecision.HELD
        if (!forwardEnabled) return SmsDecision.DROPPED_DISABLED
        return SmsDecision.FORWARDED
    }
}

fun maskAddress(from: String): String {
    val digits = from.filter { it.isDigit() }
    if (digits.length < 2) return "••"
    return "••" + digits.takeLast(2)
}

fun safeSmsId(id: String): String? =
    if (id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) id else null

interface SmsQueue {
    fun insertPending(message: SmsMessage)
    fun insertForward(message: SmsMessage)
    fun pendingMeta(): List<SmsMessage>
    fun pendingForLocal(id: String): SmsMessage?
    fun forwardList(): List<SmsMessage>
    fun approve(id: String, nowMs: Long): Boolean
    fun deny(id: String): Boolean
    fun ack(id: String): Boolean
}

class SmsIngress(
    private val queue: SmsQueue,
    private val forwardEnabled: () -> Boolean,
    private val ids: () -> String,
    private val clock: () -> Long,
) {
    fun accept(channel: Channel, from: String, body: String): IngressResult {
        val decision = SmsPolicy.decide(channel, body, forwardEnabled())
        val id = safeSmsId(ids()) ?: return IngressResult(decision, null)
        val now = clock()
        when (decision) {
            SmsDecision.FORWARDED -> queue.insertForward(
                SmsMessage(id, channel, from, body, now, null),
            )
            SmsDecision.HELD -> queue.insertPending(
                SmsMessage(id, channel, from, body, now, now + SmsPolicy.CONSENT_TTL_MS),
            )
            SmsDecision.DROPPED_RCS, SmsDecision.DROPPED_DISABLED -> Unit
        }
        return IngressResult(decision, if (decision == SmsDecision.DROPPED_RCS || decision == SmsDecision.DROPPED_DISABLED) null else id)
    }
}

fun SmsMessage.toForwardJson(): JsonValue = jobj(
    "id" to jstr(id),
    "channel" to jstr(channel.name.lowercase()),
    "from" to jstr(from),
    "body" to jstr(body),
    "receivedAtMs" to jnum(receivedAtMs),
)

fun SmsMessage.toPendingMetaJson(): JsonValue = jobj(
    "id" to jstr(id),
    "channel" to jstr(channel.name.lowercase()),
    "from" to jstr(maskAddress(from)),
    "receivedAtMs" to jnum(receivedAtMs),
    "expiresAtMs" to jnum(expiresAtMs ?: 0),
    "needsConsent" to jbool(true),
)

class FileSmsQueue(
    dir: File,
    private val clock: () -> Long = { System.currentTimeMillis() },
) : SmsQueue {
    private val pendingDir = File(dir, "pending").apply { mkdirs() }
    private val forwardDir = File(dir, "forward").apply { mkdirs() }

    @Synchronized
    override fun insertPending(message: SmsMessage) {
        write(pendingDir, message)
    }

    @Synchronized
    override fun insertForward(message: SmsMessage) {
        write(forwardDir, message)
        trim(forwardDir, MAX_FORWARD)
    }

    @Synchronized
    override fun pendingMeta(): List<SmsMessage> = livePending().map { message ->
        message.copy(from = maskAddress(message.from), body = "")
    }

    @Synchronized
    override fun pendingForLocal(id: String): SmsMessage? {
        val safe = safeSmsId(id) ?: return null
        return livePending().firstOrNull { it.id == safe }
    }

    @Synchronized
    override fun forwardList(): List<SmsMessage> = readAll(forwardDir)

    @Synchronized
    override fun approve(id: String, nowMs: Long): Boolean {
        val safe = safeSmsId(id) ?: return false
        val message = read(pendingDir, safe) ?: return false
        val expires = message.expiresAtMs
        if (expires != null && nowMs > expires) {
            file(pendingDir, safe).delete()
            return false
        }
        write(forwardDir, message.copy(expiresAtMs = null))
        file(pendingDir, safe).delete()
        trim(forwardDir, MAX_FORWARD)
        return true
    }

    @Synchronized
    override fun deny(id: String): Boolean {
        val safe = safeSmsId(id) ?: return false
        return file(pendingDir, safe).delete()
    }

    @Synchronized
    override fun ack(id: String): Boolean {
        val safe = safeSmsId(id) ?: return false
        return file(forwardDir, safe).delete()
    }

    private fun livePending(): List<SmsMessage> {
        val now = clock()
        return readAll(pendingDir).mapNotNull { message ->
            val expires = message.expiresAtMs
            if (expires != null && now > expires) {
                file(pendingDir, message.id).delete()
                null
            } else {
                message
            }
        }
    }

    private fun write(dir: File, message: SmsMessage) {
        val safe = safeSmsId(message.id) ?: return
        file(dir, safe).writeText(JsonCodec.stringify(message.toStoredJson()))
    }

    private fun read(dir: File, id: String): SmsMessage? {
        val target = file(dir, id)
        if (!target.isFile) return null
        return runCatching { parseStored(JsonCodec.parse(target.readText())) }.getOrNull()
    }

    private fun readAll(dir: File): List<SmsMessage> {
        val files = dir.listFiles() ?: return emptyList()
        return files.mapNotNull { item ->
            if (!item.isFile) return@mapNotNull null
            runCatching { parseStored(JsonCodec.parse(item.readText())) }.getOrNull()
        }.sortedBy { it.receivedAtMs }
    }

    private fun trim(dir: File, max: Int) {
        val extra = readAll(dir).dropLast(max)
        extra.forEach { file(dir, it.id).delete() }
    }

    private fun file(dir: File, id: String): File = File(dir, id + ".json")

    private fun SmsMessage.toStoredJson(): JsonValue = jobj(
        "id" to jstr(id),
        "channel" to jstr(channel.name),
        "from" to jstr(from),
        "body" to jstr(body),
        "receivedAtMs" to jnum(receivedAtMs),
        "expiresAtMs" to (expiresAtMs?.let { jnum(it) } ?: JsonValue.Null),
    )

    private fun parseStored(value: JsonValue): SmsMessage {
        val map = value.asObj() ?: throw JsonException("sms")
        return SmsMessage(
            id = map.getValue("id").asString()!!,
            channel = Channel.valueOf(map.getValue("channel").asString()!!),
            from = map.getValue("from").asString()!!,
            body = map.getValue("body").asString()!!,
            receivedAtMs = (map.getValue("receivedAtMs") as JsonValue.Num).value,
            expiresAtMs = (map["expiresAtMs"] as? JsonValue.Num)?.value,
        )
    }

    companion object {
        private const val MAX_FORWARD = 50
    }
}
