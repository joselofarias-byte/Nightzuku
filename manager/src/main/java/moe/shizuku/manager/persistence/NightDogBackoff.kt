package moe.shizuku.manager.persistence

/**
 * Bounded NightDog retry schedule.
 *
 * Kept separate from [moe.shizuku.manager.shizuku.NightDogRecovery] so the 8–60s
 * invariant can be unit-tested without starting the watchdog.
 */
object NightDogBackoff {
    const val MIN_RETRY_MS = 8_000L
    const val MAX_RETRY_MS = 60_000L
    const val POLL_INTERVAL_MS = 4_000L
    const val RECOVERY_SETTLE_MS = 1_500L

    fun retryDelayMs(failedAttempts: Int): Long {
        if (failedAttempts <= 0) return 0L
        val shift = (failedAttempts - 1).coerceAtMost(3)
        return (MIN_RETRY_MS shl shift).coerceAtMost(MAX_RETRY_MS)
    }

    fun nextRetryElapsedRealtime(
        nowElapsedRealtime: Long,
        lastAttemptElapsedRealtime: Long,
        failedAttempts: Int
    ): Long {
        val delay = retryDelayMs(failedAttempts)
        if (lastAttemptElapsedRealtime == 0L || delay == 0L) return 0L
        val next = lastAttemptElapsedRealtime + delay
        return if (next > nowElapsedRealtime) next else 0L
    }

    fun remainingRetryMs(
        nowElapsedRealtime: Long,
        lastAttemptElapsedRealtime: Long,
        failedAttempts: Int
    ): Long {
        val next = nextRetryElapsedRealtime(
            nowElapsedRealtime,
            lastAttemptElapsedRealtime,
            failedAttempts
        )
        return if (next == 0L) 0L else (next - nowElapsedRealtime).coerceAtLeast(0L)
    }

    fun allowsImmediateManualRetry(failedAttemptsAfterReset: Int): Boolean {
        return retryDelayMs(failedAttemptsAfterReset) == 0L
    }
}
