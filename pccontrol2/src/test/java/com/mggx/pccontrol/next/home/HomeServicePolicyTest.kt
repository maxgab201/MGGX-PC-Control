package com.mggx.pccontrol.next.home

import android.content.pm.ServiceInfo
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class HomeServicePolicyTest {
    @Test fun pre34DoesNotPassSpecialUseTypeToFramework() {
        assertEquals(0, homeForegroundServiceType(26))
        assertEquals(0, homeForegroundServiceType(33))
    }

    @Test fun api34AndNewerUseDeclaredSpecialUseType() {
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, homeForegroundServiceType(34))
        assertEquals(ServiceInfo.FOREGROUND_SERVICE_TYPE_SPECIAL_USE, homeForegroundServiceType(36))
    }

    @Test fun restoreRetriesAreBounded() {
        assertTrue(shouldRetryHomeRestore(0))
        assertTrue(shouldRetryHomeRestore(2))
        assertFalse(shouldRetryHomeRestore(3))
        assertFalse(shouldRetryHomeRestore(20))
    }

    @Test fun serviceLogSanitizesCredentials() {
        val sanitized = HomeServiceFailureLog.sanitize(
            "Authorization: Bearer abc.def token=secret-value pairingSecret super-secret",
        )
        assertFalse(sanitized.contains("abc.def"))
        assertFalse(sanitized.contains("secret-value"))
        assertFalse(sanitized.contains("super-secret"))
        assertTrue(sanitized.contains("<redacted>"))
    }
}
