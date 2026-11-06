package moe.shizuku.manager.dhizuku

import android.os.IBinder
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Dhizuku's UserService protocol uses FIRST_CALL_TRANSACTION + 1 / + 2 for
 * lifecycle messages. Nightzuku RPCs must never occupy those slots.
 */
class DhizukuAidlTransactionTest {

    @Test
    fun applicationRpcCodesDoNotCollideWithDhizukuLifecycle() {
        val codes = listOf(
            IDhizukuService.Stub.TRANSACTION_setAdbEnabled,
            IDhizukuService.Stub.TRANSACTION_setWirelessDebuggingEnabled,
            IDhizukuService.Stub.TRANSACTION_enableAdb,
            IDhizukuService.Stub.TRANSACTION_getAdbPort
        )

        assertEquals(codes.size, codes.distinct().size)
        assertTrue(codes.none { it == IBinder.FIRST_CALL_TRANSACTION + 1 })
        assertTrue(codes.none { it == IBinder.FIRST_CALL_TRANSACTION + 2 })
        assertTrue(codes.all { it >= IBinder.FIRST_CALL_TRANSACTION + 20 })
    }
}
