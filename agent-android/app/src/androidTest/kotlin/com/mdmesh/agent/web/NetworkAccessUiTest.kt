package com.mdmesh.agent.web

import android.content.Intent
import android.graphics.Bitmap
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Test
import org.junit.Assert.*
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class NetworkAccessUiTest {
    @Test fun networkScreenAndAdminSheetRenderInsideSystemBars() {
        val instrumentation=InstrumentationRegistry.getInstrumentation()
        val context=instrumentation.targetContext
        val activity=instrumentation.startActivitySync(Intent(context,NetworkAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) as NetworkAccessActivity
        instrumentation.waitForIdleSync()
        fun texts(view:View):List<TextView> = (if(view is TextView)listOf(view) else emptyList()) +
            (if(view is ViewGroup)(0 until view.childCount).flatMap { texts(view.getChildAt(it)) } else emptyList())
        repeat(20) {
            var ready=false
            instrumentation.runOnMainSync { ready=activity.window.decorView.width>0 && activity.window.decorView.isLaidOut }
            if(!ready)Thread.sleep(100)
        }
        instrumentation.runOnMainSync {
            val views=texts(activity.window.decorView)
            assertTrue(views.any { it.text.toString()=="Truy cập mạng" })
            assertTrue(views.any { it.text.toString()=="Bị chặn" })
            val title=views.first { it.text.toString()=="Truy cập mạng" }
            val location=IntArray(2);title.getLocationOnScreen(location)
            assertTrue("Title should clear status bar",location[1]>0)
            fun all(view:View):List<View> = listOf(view) + (if(view is ViewGroup)(0 until view.childCount).flatMap { all(view.getChildAt(it)) } else emptyList())
            val account=all(activity.window.decorView).first { it.contentDescription=="Tài khoản quản trị" }
            assertTrue("Toolbar action should have a standard touch target",account.height>=48*context.resources.displayMetrics.density)
            account.performClick()
        }
        instrumentation.waitForIdleSync()
        instrumentation.uiAutomation.takeScreenshot()?.let { bitmap ->
            java.io.File(context.getExternalFilesDir(null),"network-access-ui.png").outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG,100,it) }
        }
        instrumentation.runOnMainSync { activity.finish() }
    }
}
