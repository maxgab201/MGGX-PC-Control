package com.mggx.pccontrol.next.v2

import com.mggx.pccontrol.next.pairing.CameraPermissionState
import com.mggx.pccontrol.next.pairing.cameraPermissionState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Test

class OnboardingFlowTest {
    @Test fun progressUsesIndependentRoleLists() {
        assertEquals(OnboardingProgress(1, 8), OnboardingFlow.progress(DeviceRole.CONTROL_PHONE, OnboardingStep.CONTROL_PREPARE_PHONE))
        assertEquals(OnboardingProgress(1, 5), OnboardingFlow.progress(DeviceRole.HOME_PHONE, OnboardingStep.HOME_PREPARE_TAILSCALE))
        assertEquals(OnboardingProgress(5, 5), OnboardingFlow.progress(DeviceRole.HOME_PHONE, OnboardingStep.HOME_PAIR_CONTROL))
        assertNotEquals(OnboardingStep.HOME_PAIR_CONTROL.ordinal + 1, OnboardingFlow.progress(DeviceRole.HOME_PHONE, OnboardingStep.HOME_PAIR_CONTROL).current)
    }

    @Test fun deniedCameraHasExplicitFallbackState() {
        assertEquals(CameraPermissionState.DENIED, cameraPermissionState(false))
        assertEquals(CameraPermissionState.GRANTED, cameraPermissionState(true))
    }
}
