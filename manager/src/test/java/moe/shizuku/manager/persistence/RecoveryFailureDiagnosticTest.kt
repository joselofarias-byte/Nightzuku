package moe.shizuku.manager.persistence

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RecoveryFailureDiagnosticTest {

    @Test
    fun traceDetailKeepsStableCodeTransportAndEndpoint() {
        val detail = RecoveryFailureDiagnostic(
            reason = RecoveryFailureReason.ADB_CONNECT_FAILED,
            detail = "connection refused",
            transport = RecoveryTransport.DYNAMIC_LOCAL_WIRELESS_ADB,
            endpoint = "127.0.0.1:42464"
        ).traceDetail()

        assertTrue(detail.contains("code=ADB_CONNECT_FAILED"))
        assertTrue(detail.contains("transport=DYNAMIC_LOCAL_WIRELESS_ADB"))
        assertTrue(detail.contains("endpoint=127.0.0.1:42464"))
        assertTrue(detail.contains("detail=connection refused"))
    }

    @Test
    fun traceDetailIsSingleLine() {
        val detail = RecoveryFailureDiagnostic(
            reason = RecoveryFailureReason.SERVER_START_FAILED,
            detail = "line one\nline two\rline three"
        ).traceDetail()

        assertFalse(detail.contains('\n'))
        assertFalse(detail.contains('\r'))
        assertTrue(detail.contains("line one line two line three"))
    }
}
