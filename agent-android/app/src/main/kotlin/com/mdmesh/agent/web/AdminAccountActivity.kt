package com.mdmesh.agent.web

import android.Manifest
import android.os.Build
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.view.View
import android.widget.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.materialswitch.MaterialSwitch
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.AgentUi
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/** The same account entry is reachable from home and every management screen. */
class AdminAccountActivity:AppCompatActivity() {
    private lateinit var client:NetworkAccessClient
    private var busy=false
    private var message=""
    private val permission=registerForActivityResult(ActivityResultContracts.RequestPermission()){granted ->
        if(granted)enableNotifications() else {message="Cần cho phép thông báo để nhận yêu cầu.";render()}
    }
    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        client=NetworkAccessClient(applicationContext);render()
    }
    private fun render() {
        val root=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setBackgroundColor(getColor(R.color.network_background))}
        root.addView(AgentUi.toolbar(this,"Tài khoản quản trị",account=false))
        val body=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(AgentUi.dp(this@AdminAccountActivity,8),AgentUi.dp(this@AdminAccountActivity,16),AgentUi.dp(this@AdminAccountActivity,8),AgentUi.dp(this@AdminAccountActivity,24))}
        fun line(value:String,size:Float=14f){body.addView(TextView(this).apply{text=value;textSize=size;setTextColor(getColor(R.color.network_on_surface));setPadding(0,0,0,AgentUi.dp(this@AdminAccountActivity,16))})}
        if(client.isAdmin()) {
            line(client.accountName,22f)
            line("Đã đăng nhập · dùng chung trên tất cả màn hình Agent. Phiên được giữ khi quay về màn hình chính hoặc mở lại ứng dụng, đến khi đăng xuất hoặc server hết hạn phiên.")
            val account=NetworkAdminAccount(this)
            body.addView(MaterialSwitch(this).apply {
                text="Nhận yêu cầu khi bật màn hình";isChecked=account.token()!=null;isEnabled=!busy
                setOnCheckedChangeListener { _,checked ->
                    if(checked){
                        if(Build.VERSION.SDK_INT>=33&&checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS)!=android.content.pm.PackageManager.PERMISSION_GRANTED)permission.launch(Manifest.permission.POST_NOTIFICATIONS)
                        else enableNotifications()
                    }else runAction {
                        val token=account.token();account.clear();NetworkAdminNotificationService.stop(this@AdminAccountActivity)
                        token?.let {runCatching {client.receive(it,true)}};message="Đã tắt nhận yêu cầu."
                    }
                }
            })
            line("Chỉ kiểm tra khi màn hình sáng và có mạng. Android sẽ hiển thị một thông báo nền khi bật tính năng này.")
            body.addView(AgentUi.button(this,"Đăng xuất",primary=false).apply {isEnabled=!busy;setOnClickListener {runAction {
                val token=account.token();account.clear();NetworkAdminNotificationService.stop(this@AdminAccountActivity)
                client.logout();token?.let {runCatching {client.receive(it,true)}};message="Đã đăng xuất trên tất cả màn hình."
            }}})
        } else {
            line("Đăng nhập admin",22f)
            line("Dùng tài khoản MDMesh của bạn. Không lưu mật khẩu trên điện thoại.")
            val username=EditText(this).apply {hint="Tài khoản MDMesh";inputType=InputType.TYPE_CLASS_TEXT;isSingleLine=true}
            val password=EditText(this).apply {hint="Mật khẩu";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false;isSingleLine=true}
            body.addView(username);body.addView(password)
            body.addView(AgentUi.button(this,if(busy)"Đang đăng nhập…" else "Đăng nhập").apply {
                isEnabled=!busy;setOnClickListener {
                    val user=username.text.toString().trim();val pass=password.text.toString()
                    if(user.isBlank()){username.error="Nhập tài khoản";return@setOnClickListener}
                    if(pass.isEmpty()){password.error="Nhập mật khẩu";return@setOnClickListener}
                    password.text.clear()
                    runAction {client.login(user,pass);message="Đăng nhập thành công."}
                }
            })
        }
        val receiving=NetworkAdminAccount(this)
        if(!client.isAdmin()&&receiving.token()!=null){
            line("Đang nhận thông báo cho ${receiving.name()}. Đăng nhập để xem chi tiết và duyệt yêu cầu.")
            body.addView(AgentUi.button(this,"Tắt nhận yêu cầu",primary=false).apply {isEnabled=!busy;setOnClickListener {runAction {
                val token=receiving.token();receiving.clear();NetworkAdminNotificationService.stop(this@AdminAccountActivity)
                token?.let {runCatching {client.receive(it,true)}};message="Đã tắt nhận yêu cầu."
            }}})
        }
        if(message.isNotBlank())line(message)
        root.addView(ScrollView(this).apply {addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        AgentUi.insets(this,root);setContentView(root)
    }
    private fun runAction(block:suspend ()->Unit) {
        if(busy)return
        busy=true;render()
        lifecycleScope.launch {
            try{block()}catch(e:Exception){if(e is CancellationException)throw e;message=e.message ?: "Không thực hiện được"}
            finally{busy=false;if(!isFinishing)render()}
        }
    }
    private fun enableNotifications()=runAction {
        val result=client.admin("/private/network-access/notifications/register",org.json.JSONObject().put("installationId",NetworkAdminAccount(this).installation()))
        NetworkAdminAccount(this).save(result.getJSONObject("data"));NetworkAdminNotificationService.start(this)
        message="Đã bật nhận yêu cầu."
    }
}
