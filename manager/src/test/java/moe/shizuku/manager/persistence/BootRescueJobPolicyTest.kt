package moe.shizuku.manager.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class BootRescueJobPolicyTest {
    @Test fun repeatedBinderReconnectsKeepTheOriginalPeriodicWindow() {
        repeat(100) {
            assertTrue(BootRescueJobPolicy.keepExisting(true, true, true, 900_000, 900_000))
        }
    }

    @Test fun oldOrIncorrectJobsAreReplaced() {
        assertFalse(BootRescueJobPolicy.keepExisting(false, true, true, 900_000, 900_000))
        assertFalse(BootRescueJobPolicy.keepExisting(true, false, true, 900_000, 900_000))
        assertFalse(BootRescueJobPolicy.keepExisting(true, true, false, 900_000, 900_000))
        assertFalse(BootRescueJobPolicy.keepExisting(true, true, true, 1_800_000, 900_000))
    }
}
