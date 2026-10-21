package com.mdmesh.agent.ui

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.view.MenuItem
import android.view.View
import android.widget.LinearLayout
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.doOnAttach
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButton
import com.mdmesh.agent.R
import com.mdmesh.agent.web.AdminAccountActivity

/** Shared visual primitives for the Agent; keep role and screen content independent. */
internal object AgentUi {
    fun requestDeviceText(row: org.json.JSONObject): String {
        return "Thiết bị yêu cầu: ${requestDeviceLabel(row.optString("deviceName"), row.optString("deviceNumber"))}"
    }
    fun requestDevice(body: LinearLayout, row: org.json.JSONObject) {
        body.addView(android.widget.TextView(body.context).apply {
            text = requestDeviceText(row)
            textSize = 16f
            setTextColor(body.context.getColor(R.color.network_on_surface))
            setTypeface(typeface, android.graphics.Typeface.BOLD)
            setPadding(0, dp(body.context, 8), 0, dp(body.context, 16))
            setTextIsSelectable(true)
        })
    }
    fun dp(context:Context,value:Int)=(value*context.resources.displayMetrics.density).toInt()
    fun toolbar(activity:Activity,title:String,back:Boolean=true,account:Boolean=true)=MaterialToolbar(activity).apply {
        this.title=title;setTitleTextColor(activity.getColor(R.color.network_on_surface))
        setBackgroundColor(activity.getColor(R.color.network_background))
        if(back){setNavigationIcon(R.drawable.network_back);navigationContentDescription="Quay lại";setNavigationIconTint(activity.getColor(R.color.network_on_surface));setNavigationOnClickListener { activity.finish() }}
        if(account){menu.add("Tài khoản quản trị").apply {setIcon(R.drawable.network_account);setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)};setOnMenuItemClickListener {activity.startActivity(Intent(activity,AdminAccountActivity::class.java));true}}
        layoutParams=LinearLayout.LayoutParams(-1,dp(activity,56))
    }
    fun button(context:Context,title:String,primary:Boolean=true)=MaterialButton(context,null,if(primary)com.google.android.material.R.attr.materialButtonStyle else com.google.android.material.R.attr.borderlessButtonStyle).apply {
        text=title;isAllCaps=false;textSize=14f;cornerRadius=dp(context,12);minimumHeight=dp(context,48);insetTop=0;insetBottom=0;elevation=0f
        layoutParams=LinearLayout.LayoutParams(-1,dp(context,48)).apply {topMargin=dp(context,12)}
    }
    fun insets(activity:Activity,view:View,horizontal:Int=16,vertical:Int=0) {
        WindowCompat.setDecorFitsSystemWindows(activity.window,false)
        ViewCompat.setOnApplyWindowInsetsListener(view){v,insets ->
            val bars=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(activity,horizontal)+bars.left,dp(activity,vertical)+bars.top,dp(activity,horizontal)+bars.right,dp(activity,vertical)+bars.bottom);insets
        }
        view.doOnAttach {ViewCompat.requestApplyInsets(it)}
    }
}
