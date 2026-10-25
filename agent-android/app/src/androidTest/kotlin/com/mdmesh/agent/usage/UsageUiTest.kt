package com.mdmesh.agent.usage

import android.app.Application
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.agent.ui.compose.MDMeshTheme
import kotlinx.coroutines.runBlocking
import org.json.JSONObject
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test

class UsageUiTest {
    @get:Rule val compose=createComposeRule()
    private class Server:UsageTransport {
        var saved:JSONObject?=null
        var savedPath=""
        var metadataCalls=0
        var summaryCalls=0
        private val policies=mutableMapOf<String,JSONObject>()
        private val initial=JSONObject("""{"timezone":"Asia/Ho_Chi_Minh","rules":[{"packageName":"com.other.app","dailyLimitMinutes":15,"enabled":true,"action":"suspend","warningMinutes":5,"allowedWindows":[]}],"schedules":[]}""")
        override fun isAdmin()=true
        override suspend fun admin(path:String,body:JSONObject?,method:String?):JSONObject {
            if(path.endsWith("/configurations/list")||path.endsWith("/applications/search"))metadataCalls++
            if(path.contains("/summary?"))summaryCalls++
            val data:Any=when {
                path.endsWith("/configurations/list")->org.json.JSONArray("""[{"id":1,"name":"Phòng học"},{"id":2,"name":"Gia đình"},{"id":3,"name":"Thử nghiệm"}]""")
                path.endsWith("/applications/search")->org.json.JSONArray("""[{"pkg":"com.google.android.youtube","name":"YouTube"}]""")
                path.contains("/summary?")->org.json.JSONArray("""[{"packageName":"com.google.android.youtube","configurationId":1,"foregroundMs":3600000,"deviceCount":2},{"packageName":"com.google.android.youtube","configurationId":2,"foregroundMs":7200000,"deviceCount":3}]""")
                method=="PUT"->{saved=JSONObject(body.toString());savedPath=path;policies[path]=JSONObject(body.toString());policies.getValue(path)}
                else->JSONObject((policies[path]?:initial).toString())
            }
            return JSONObject().put("status","OK").put("data",data)
        }
    }
    @Test fun ranksConfigurationsAndSavesOnlySelectedConfiguration() {
        val server=Server()
        lateinit var model:UsageViewModel
        compose.runOnUiThread { model=UsageViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,server) }
        compose.setContent { MDMeshTheme {UsageScreen(model){}} }
        compose.waitUntil(10000){model.state.value.apps.isNotEmpty()}
        compose.onNodeWithText("Tất cả cấu hình").assertIsDisplayed()
        capture("usage-list.png")
        compose.onNodeWithText("YouTube").performClick()
        compose.waitUntil(10000){model.state.value.policy!=null}
        capture("usage-sheet.png")
        assertEquals(2,model.state.value.configuration)
        assertEquals(listOf(2,1,3),model.state.value.selected!!.configurations.map{it.id})
        compose.onNodeWithText("Gia đình",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Phòng học",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){model.state.value.configuration==1&&model.state.value.policy!=null}
        compose.onNodeWithText("Giới hạn phút/ngày").performTextInput("60")
        compose.onNode(isToggleable()).performClick()
        compose.onNodeWithText("Lưu cho Phòng học").performScrollTo().performClick()
        compose.waitUntil(10000){server.saved!=null&&!model.state.value.saving}
        assertEquals("/private/app-usage/configuration/1",server.savedPath)
        val saved=server.saved!!
        assertEquals(2,saved.getJSONArray("rules").length())
        assertEquals(15,saved.getJSONArray("rules").getJSONObject(0).getInt("dailyLimitMinutes"))
        assertEquals(60,saved.getJSONArray("rules").getJSONObject(1).getInt("dailyLimitMinutes"))
        assertEquals(2,saved.getJSONArray("schedules").length())
        assertEquals("com.google.android.youtube",saved.getJSONArray("schedules").getJSONObject(0).getJSONArray("packages").getString(0))
        compose.runOnUiThread{model.close();model.open(model.state.value.apps.first())}
        compose.waitUntil(10000){model.state.value.policy!=null}
        assertEquals(2,model.state.value.configuration)
        compose.onNodeWithText("Giới hạn phút/ngày").assertTextContains("")
        compose.onNodeWithText("Gia đình",useUnmergedTree=true).performClick()
        compose.onNodeWithText("Phòng học",useUnmergedTree=true).performClick()
        compose.waitUntil(10000){model.state.value.configuration==1&&model.state.value.policy!=null}
        compose.onNodeWithText("Giới hạn phút/ngày").assertTextContains("60")
    }
    @Test fun typingDoesNotRefetchAndDateChangesReuseMetadata() {
        val server=Server()
        lateinit var model:UsageViewModel
        compose.runOnUiThread {model=UsageViewModel(InstrumentationRegistry.getInstrumentation().targetContext.applicationContext as Application,server)}
        compose.setContent {MDMeshTheme {UsageScreen(model){}}}
        compose.waitUntil(10000){model.state.value.apps.isNotEmpty()}
        compose.onNodeWithText("Tìm ứng dụng").performTextInput("You")
        compose.runOnIdle {assertEquals(1,server.summaryCalls);assertEquals(2,server.metadataCalls)}
        compose.onNodeWithText("7 ngày").performClick()
        compose.waitUntil(10000){model.state.value.days==7&&!model.state.value.loading}
        compose.runOnIdle {assertEquals(2,server.summaryCalls);assertEquals(2,server.metadataCalls)}
    }
    private fun capture(name:String) {
        compose.waitForIdle()
        android.os.SystemClock.sleep(350)
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val bitmap=instrumentation.uiAutomation.takeScreenshot()
        java.io.File(instrumentation.targetContext.filesDir,name).outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) }
        bitmap.recycle()
    }
}
