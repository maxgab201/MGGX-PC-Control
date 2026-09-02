package com.mggx.pccontrol.next

import android.Manifest
import android.os.SystemClock
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.rule.GrantPermissionRule
import com.mggx.pccontrol.next.pairing.PairingCameraScreen
import com.mggx.pccontrol.next.pairing.PairingQrKind
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/** Regression for CameraX dispatching analyzer frames after the scanner dialog is disposed. */
@RunWith(AndroidJUnit4::class)
class ScannerLifecycleReproTest {
    @get:Rule(order = 0)
    val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @get:Rule(order = 1)
    val compose = createComposeRule()

    @Test
    fun closingScannerWhileCameraIsActiveDoesNotCrashProcess() {
        val visible = mutableStateOf(true)
        compose.setContent {
            MaterialTheme {
                if (visible.value) {
                    PairingCameraScreen(PairingQrKind.HOME_DEVICE, onClose = {}, onValid = {})
                }
            }
        }
        compose.waitForIdle()
        SystemClock.sleep(2_000)
        compose.runOnUiThread { visible.value = false }
        compose.waitForIdle()
        // Alpha 3 left ImageAnalysis bound to the resumed Activity while shutting down its
        // executor. Keeping the process alive for more camera frames reproduces that race.
        SystemClock.sleep(4_000)
    }
}
