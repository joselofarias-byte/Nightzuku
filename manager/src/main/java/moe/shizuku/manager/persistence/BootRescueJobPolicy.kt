package moe.shizuku.manager.persistence

/** Preserve an unchanged periodic job's Android-managed execution window. */
object BootRescueJobPolicy {
    fun keepExisting(
        persisted: Boolean,
        periodic: Boolean,
        sameService: Boolean,
        intervalMs: Long,
        requiredIntervalMs: Long
    ): Boolean = persisted && periodic && sameService && intervalMs == requiredIntervalMs
}
