package com.mggx.pccontrol.next

import androidx.compose.material3.MaterialTheme
import androidx.compose.ui.test.assertIsNotEnabled
import androidx.compose.ui.test.assertIsEnabled
import androidx.compose.ui.test.assertIsDisplayed
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.test.onNodeWithText
import org.junit.Rule
import org.junit.Test

/** Instrumented checks for immediate text and duplicate-tap protection in primary actions. */
class ActionFeedbackComposeTest {
    @get:Rule val compose = createComposeRule()

    @Test fun actionButtonRendersLoadingImmediatelyAndDisablesDuplicateTap() {
        compose.setContent {
            MaterialTheme {
                ActionButton({}, ActionState.Loading, "GUARDAR", "GUARDANDO…")
            }
        }
        compose.onNodeWithText("GUARDANDO…").assertIsDisplayed().assertIsNotEnabled()
    }

    @Test fun actionButtonRendersConfirmedSuccess() {
        compose.setContent {
            MaterialTheme {
                ActionButton({}, ActionState.Success("Guardado"), "GUARDAR", "GUARDANDO…")
            }
        }
        compose.onNodeWithText("GUARDADO").assertIsDisplayed()
    }

    @Test fun actionButtonRendersErrorWithoutFalseSuccess() {
        compose.setContent { MaterialTheme { ActionButton({}, ActionState.Error("falló"), "GUARDAR", "GUARDANDO…") } }
        compose.onNodeWithText("ERROR").assertIsDisplayed()
    }

    @Test fun idleActionIsEnabledAndHasImmediateLabel() {
        compose.setContent { MaterialTheme { ActionButton({}, ActionState.Idle, "PROBAR CONEXIÓN", "PROBANDO…") } }
        compose.onNodeWithText("PROBAR CONEXIÓN").assertIsDisplayed().assertIsEnabled()
    }
}
