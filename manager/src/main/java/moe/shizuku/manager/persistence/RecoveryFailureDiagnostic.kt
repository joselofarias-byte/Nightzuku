package moe.shizuku.manager.persistence

/**
 * Stable recovery failure taxonomy for NightDog.
 *
 * Keep these enum names stable: they are written to the device-protected boot trace and are
 * intended to survive wording changes in the UI. Human-readable details remain separate.
 */
enum class RecoveryFailureReason {
    NO_TRANSPORT,
    ADB_REENABLE_FAILED,
    WIRELESS_REENABLE_FAILED,
    DHIZUKU_NOT_AUTHORIZED,
    DHIZUKU_RECOVERY_FAILED,
    ADB_CONNECT_FAILED,
    SERVER_START_FAILED,
    BINDER_TIMEOUT,
    TRANSPORT_CLEANUP_FAILED,
    WIRELESS_CLEANUP_FAILED,
    TRANSPORT_CLEANUP_DESTABILIZED
}

data class RecoveryFailureDiagnostic(
    val reason: RecoveryFailureReason,
    val detail: String?,
    val transport: RecoveryTransport = RecoveryTransport.NONE,
    val endpoint: String? = null
) {
    fun traceDetail(): String {
        val fields = mutableListOf(
            "code=${reason.name}",
            "transport=${transport.name}"
        )
        endpoint?.takeIf { it.isNotBlank() }?.let { fields += "endpoint=${sanitize(it)}" }
        detail?.takeIf { it.isNotBlank() }?.let { fields += "detail=${sanitize(it)}" }
        return fields.joinToString(" ")
    }

    private fun sanitize(value: String): String =
        value
            .replace('\n', ' ')
            .replace('\r', ' ')
            .replace('|', '/')
            .trim()
            .take(MAX_TRACE_FIELD_LENGTH)

    private companion object {
        const val MAX_TRACE_FIELD_LENGTH = 240
    }
}
