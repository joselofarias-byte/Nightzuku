package moe.shizuku.manager.persistence

import kotlinx.coroutines.delay

/** Wait for stable Binder without performing any settings writes after a manual stop. */
object RecoveryCleanupPolicy {
    suspend fun awaitStableBinder(
        checks: Int,
        intervalMs: Long,
        desiredRunning: () -> Boolean,
        binderAlive: () -> Boolean,
        wait: suspend (Long) -> Unit = { delay(it) }
    ): Boolean {
        if (!desiredRunning()) return false
        repeat(checks.coerceAtLeast(1)) {
            wait(intervalMs)
            if (!desiredRunning() || !binderAlive()) return false
        }
        return true
    }
}
