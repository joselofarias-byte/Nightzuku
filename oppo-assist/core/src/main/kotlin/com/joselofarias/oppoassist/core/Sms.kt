package com.joselofarias.oppoassist.core

import java.io.File

enum class Channel { SMS, RCS }

enum class SmsDecision { FORWARDED, DROPPED_RCS }

data class SmsMessage(
    val id: String,
    val channel: Channel,
    val from: String,
    val body: String,
    val receivedAtMs: Long,
    val otp: Boolean,
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
    fun decide(channel: Channel): SmsDecision {
        if (channel == Channel.RCS) return SmsDecision.DROPPED_RCS
        return SmsDecision.FORWARDED
    }
}

fun safeSmsId(id: String): String? =
    if (id.matches(Regex("[A-Za-z0-9._-]{1,80}"))) id else null

interface SmsQueue {
    fun insertForward(message: SmsMessage)
    fun forwardList(): List<SmsMessage>
    fun ack(id: String): Boolean
}

class SmsIngress(
    private val queue: SmsQueue,
    private val ids: () -> String,
    private val clock: () -> Long,
) {
    fun accept(channel: Channel, from: String, body: String): IngressResult {
        val decision = SmsPolicy.decide(channel)
        if (decision == SmsDecision.DROPPED_RCS) return IngressResult(decision, null)
        val id = safeSmsId(ids()) ?: return IngressResult(decision, null)
        queue.insertForward(
            SmsMessage(
                id = id,
                channel = channel,
                from = from,
                body = body,
                receivedAtMs = clock(),
                otp = SensitiveSms.looksLikeOneTimeCode(body),
            ),
        )
        return IngressResult(decision, id)
    }
}

fun SmsMessage.toForwardJson(): JsonValue = jobj(
    "id" to jstr(id),
    "channel" to jstr(channel.name.lowercase()),
    "from" to jstr(from),
    "body" to jstr(body),
    "receivedAtMs" to jnum(receivedAtMs),
    "otp" to jbool(otp),
)

class FileSmsQueue(dir: File) : SmsQueue {
    private val forwardDir = File(dir, "forward").apply { mkdirs() }

    @Synchronized
    override fun insertForward(message: SmsMessage) {
        write(message)
        trim(MAX_FORWARD)
    }

    @Synchronized
    override fun forwardList(): List<SmsMessage> = readAll()

    @Synchronized
    override fun ack(id: String): Boolean {
        val safe = safeSmsId(id) ?: return false
        return file(safe).delete()
    }

    private fun write(message: SmsMessage) {
        val safe = safeSmsId(message.id) ?: return
        file(safe).writeText(JsonCodec.stringify(message.toStoredJson()))
    }

    private fun readAll(): List<SmsMessage> {
        val files = forwardDir.listFiles() ?: return emptyList()
        return files.mapNotNull { item ->
            if (!item.isFile) return@mapNotNull null
            runCatching { parseStored(JsonCodec.parse(item.readText())) }.getOrNull()
        }.sortedBy { it.receivedAtMs }
    }

    private fun trim(max: Int) {
        readAll().dropLast(max).forEach { file(it.id).delete() }
    }

    private fun file(id: String): File = File(forwardDir, "$id.json")

    private fun SmsMessage.toStoredJson(): JsonValue = jobj(
        "id" to jstr(id),
        "channel" to jstr(channel.name),
        "from" to jstr(from),
        "body" to jstr(body),
        "receivedAtMs" to jnum(receivedAtMs),
        "otp" to jbool(otp),
    )

    private fun parseStored(value: JsonValue): SmsMessage {
        val map = value.asObj() ?: throw JsonException("sms")
        return SmsMessage(
            id = map.getValue("id").asString()!!,
            channel = Channel.valueOf(map.getValue("channel").asString()!!),
            from = map.getValue("from").asString()!!,
            body = map.getValue("body").asString()!!,
            receivedAtMs = (map.getValue("receivedAtMs") as JsonValue.Num).value,
            otp = (map["otp"] as? JsonValue.Bool)?.value == true,
        )
    }

    companion object {
        private const val MAX_FORWARD = 50
    }
}
