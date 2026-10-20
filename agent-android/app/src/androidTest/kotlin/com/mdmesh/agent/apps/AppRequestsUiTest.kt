package com.mdmesh.agent.apps

import android.content.Intent
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class AppRequestsUiTest {
    private fun texts(view: View): List<TextView> = (if(view is TextView)listOf(view) else emptyList()) +
        (if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
    @Test fun shareTargetIsExportedButManagementScreenIsPrivate() {
        val context=InstrumentationRegistry.getInstrumentation().targetContext
        val info=context.packageManager.getActivityInfo(android.content.ComponentName(context,PlayShareActivity::class.java),0)
        assertTrue(info.exported)
        val privateInfo=context.packageManager.getActivityInfo(android.content.ComponentName(context,AppRequestsActivity::class.java),0)
        assertFalse(privateInfo.exported)
        val targets=context.packageManager.queryIntentActivities(Intent(Intent.ACTION_SEND).setType("text/plain").setPackage(context.packageName),0)
        assertTrue(targets.any { it.activityInfo.name==PlayShareActivity::class.java.name })
    }
    @Test fun userScreenProvidesBothSelectionPathsWithSafeToolbar() {
        val instrumentation=InstrumentationRegistry.getInstrumentation();val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,AppRequestsActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as AppRequestsActivity
        instrumentation.waitForIdleSync()
        instrumentation.runOnMainSync {
            val views=texts(activity.window.decorView)
            assertTrue(views.any{it.text.toString()=="Tìm ứng dụng"})
            assertTrue(views.any{it.text.toString()=="Tìm trong Google Play"})
            assertTrue(views.any{it.text.toString().contains("Chia sẻ → MDMesh")})
            val title=views.first{it.text.toString()=="Yêu cầu cài ứng dụng"}
            val xy=IntArray(2);title.getLocationOnScreen(xy);assertTrue(xy[1]>0)
        }
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap -> java.io.File(context.getExternalFilesDir(null),"app-requests-ui.png").outputStream().use { bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG,100,it) } }
        instrumentation.runOnMainSync{activity.finish()}
    }
}
