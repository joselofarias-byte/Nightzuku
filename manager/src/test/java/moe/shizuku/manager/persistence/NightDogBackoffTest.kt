package moe.shizuku.manager.persistence

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class NightDogBackoffTest {

    @Test
    fun firstAutomaticFailureWaitsEightSeconds() {
        assertEquals(8_000L, NightDogBackoff.retryDelayMs(1))
    }

    @Test
    fun backoffDoublesUntilSixtySeconds() {
        assertEquals(16_000L, NightDogBackoff.retryDelayMs(2))
        assertEquals(32_000L, NightDogBackoff.retryDelayMs(3))
        assertEquals(60_000L, NightDogBackoff.retryDelayMs(4))
        assertEquals(60_000L, NightDogBackoff.retryDelayMs(12))
    }

    @Test
    fun zeroFailuresHaveNoWait() {
        assertEquals(0L, NightDogBackoff.retryDelayMs(0))
        assertEquals(0L, NightDogBackoff.retryDelayMs(-1))
        assertTrue(NightDogBackoff.allowsImmediateManualRetry(0))
        assertFalse(NightDogBackoff.allowsImmediateManualRetry(1))
    }

    @Test
    fun automaticRetryStaysInsideBounds() {
        for (attempts in 1..20) {
            val delay = NightDogBackoff.retryDelayMs(attempts)
            assertTrue(delay >= NightDogBackoff.MIN_RETRY_MS)
            assertTrue(delay <= NightDogBackoff.MAX_RETRY_MS)
        }
    }

    @Test
    fun remainingRetryIsZeroAfterImmediateReset() {
        val now = 100_000L
        assertEquals(0L, NightDogBackoff.remainingRetryMs(now, 0L, 0))
        assertEquals(0L, NightDogBackoff.nextRetryElapsedRealtime(now, 0L, 0))
    }

    @Test
    fun remainingRetryCountsDownInsideWindow() {
        val lastAttempt = 10_000L
        val now = 12_000L
        assertEquals(18_000L, NightDogBackoff.nextRetryElapsedRealtime(now, lastAttempt, 2))
        assertEquals(6_000L, NightDogBackoff.remainingRetryMs(now, lastAttempt, 2))
        assertEquals(0L, NightDogBackoff.remainingRetryMs(30_000L, lastAttempt, 2))
    }
}
