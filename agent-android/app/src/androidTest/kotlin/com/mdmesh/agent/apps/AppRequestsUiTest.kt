package com.mdmesh.agent.apps

import android.content.ComponentName
import android.content.Intent
import androidx.compose.runtime.*
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.agent.ui.compose.MDMeshTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

/** UI fixtures use typed screen state only, never enrollment or admin credentials. */
class AppRequestsUiTest {
    @get:Rule val compose = createComposeRule()
    @Test fun shareTargetIsExportedButManagementScreenIsPrivate() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assertTrue(context.packageManager.getActivityInfo(ComponentName(context, PlayShareActivity::class.java), 0).exported)
        assertFalse(context.packageManager.getActivityInfo(ComponentName(context, AppRequestsActivity::class.java), 0).exported)
        val targets = context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName), 0)
        assertTrue(targets.any { it.activityInfo.name == PlayShareActivity::class.java.name })
    }
    @Test fun pickerHasBothPathsAndTypingDoesNotStartSearch() {
        var searches = 0
        compose.setContent {
            var state by remember { mutableStateOf(AppRequestsState()) }
            MDMeshTheme { AppRequestsScreen(state, AppRequestsActions(
                picker = { state = state.copy(sheet = AppRequestSheet.Picker) },
                query = { state = state.copy(query = it) }, search = { searches++ },
                dismiss = { state = state.copy(sheet = null) },
            )) }
        }
        compose.onNodeWithText("Yêu cầu mới").assertIsDisplayed().performClick()
        compose.onNodeWithTag("app-query").performTextInput("Zalo")
        compose.runOnIdle { assertEquals(0, searches) }
        compose.onNodeWithText("Tìm trong Google Play").assertIsDisplayed()
        compose.onNodeWithText("Tìm kiếm").performClick()
        compose.runOnIdle { assertEquals(1, searches) }
    }
    @Test fun requestDetailsPrioritizeNameAndOnlyFallbackWhenMissing() {
        val row = AppInstallRequest(99, "com.zing.zalo", "Zalo", deviceName = "Realme v15", deviceNumber = "fixture-device-number", status = "pending", reason = "Work")
        var state by mutableStateOf(AppRequestsState(admin = true, signedIn = true, requests = listOf(row)))
        compose.setContent { MDMeshTheme { AppRequestsScreen(state, AppRequestsActions(details = { state = state.copy(sheet = AppRequestSheet.Details(it)) })) } }
        compose.onNodeWithTag("request-99").performClick()
        compose.onNodeWithTag("request-device").assertTextEquals("Thiết bị yêu cầu: Realme v15")
        compose.onAllNodesWithText("fixture-device-number", substring = true).assertCountEquals(0)
        compose.runOnIdle { state = state.copy(sheet = AppRequestSheet.Details(row.copy(deviceName = " "))) }
        compose.onNodeWithTag("request-device").assertTextEquals("Thiết bị yêu cầu: fixture-device-number")
        compose.onNodeWithText("Duyệt").assertIsDisplayed()
    }
}
