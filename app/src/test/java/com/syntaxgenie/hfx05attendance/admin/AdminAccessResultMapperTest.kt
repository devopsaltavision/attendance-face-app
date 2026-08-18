package com.syntaxgenie.hfx05attendance.admin

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class AdminAccessResultMapperTest {
    @Test fun http200IsSuccess() {
        assertEquals(AdminAuthResult.Success, AdminAccessResultMapper.fromHttpStatus(200))
    }

    @Test fun http401IsSessionInvalid() {
        assertEquals(AdminAuthResult.SessionInvalid, AdminAccessResultMapper.fromHttpStatus(401))
    }

    @Test fun http403IsPermissionDenied() {
        assertEquals(AdminAuthResult.PermissionDenied, AdminAccessResultMapper.fromHttpStatus(403))
    }

    @Test fun http5xxIsServiceUnavailable() {
        listOf(500, 502, 503, 599).forEach { status ->
            assertEquals(AdminAuthResult.ServiceUnavailable, AdminAccessResultMapper.fromHttpStatus(status))
        }
    }

    @Test fun unexpectedStatusIsSafeFailure() {
        assertTrue(AdminAccessResultMapper.fromHttpStatus(418) is AdminAuthResult.Failure)
    }
}
