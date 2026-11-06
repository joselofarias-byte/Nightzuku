package moe.shizuku.manager.adb

import moe.shizuku.manager.adb.AdbProtocol.A_AUTH
import moe.shizuku.manager.adb.AdbProtocol.A_CNXN
import moe.shizuku.manager.adb.AdbProtocol.A_STLS
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AdbLocalWirelessDiscoveryTest {

    @Test
    fun acceptsOnlyValidAdbHandshakeHeaders() {
        fun magic(command: Int) = (command.toLong() xor 0xFFFFFFFFL).toInt()

        assertTrue(AdbLocalWirelessDiscovery.isAdbHeader(A_CNXN, magic(A_CNXN)))
        assertTrue(AdbLocalWirelessDiscovery.isAdbHeader(A_AUTH, magic(A_AUTH)))
        assertTrue(AdbLocalWirelessDiscovery.isAdbHeader(A_STLS, magic(A_STLS)))

        assertFalse(AdbLocalWirelessDiscovery.isAdbHeader(A_CNXN, 0))
        assertFalse(AdbLocalWirelessDiscovery.isAdbHeader(0x12345678, magic(0x12345678)))
    }
}
