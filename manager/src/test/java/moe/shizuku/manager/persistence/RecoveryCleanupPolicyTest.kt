package moe.shizuku.manager.persistence

import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryCleanupPolicyTest {
    @Test fun stableBinderMustSurviveEveryPoll() = runBlocking {
        var probes = 0
        var waited = 0L
        assertTrue(RecoveryCleanupPolicy.awaitStableBinder(10, 500, { true },
            { probes++; true }, { waited += it }))
        assertEquals(10, probes)
        assertEquals(5_000L, waited)
    }

    @Test fun manualStopDuringSettlePreventsCleanup() = runBlocking {
        var desired = true
        var waits = 0
        var probes = 0
        assertFalse(RecoveryCleanupPolicy.awaitStableBinder(10, 500, { desired },
            { probes++; true }, { if (++waits == 3) desired = false }))
        assertEquals(2, probes)
    }

    @Test fun lateBinderWhileStoppedDoesNotEvenWait() = runBlocking {
        assertFalse(RecoveryCleanupPolicy.awaitStableBinder(10, 500, { false },
            { error("Must not probe") }, { error("Must not wait") }))
    }

    @Test fun lostBinderAbortsCleanup() = runBlocking {
        assertFalse(RecoveryCleanupPolicy.awaitStableBinder(10, 500, { true }, { false }, {}))
    }

    @Test fun cancelledCleanupCannotReachSettingsWrites() = runBlocking {
        val entered = CompletableDeferred<Unit>()
        val hold = CompletableDeferred<Unit>()
        var reachedCleanup = false
        val job = launch {
            if (RecoveryCleanupPolicy.awaitStableBinder(10, 500, { true }, { true }, {
                entered.complete(Unit)
                hold.await()
            })) reachedCleanup = true
        }
        entered.await()
        job.cancelAndJoin()
        assertFalse(reachedCleanup)
    }
}
