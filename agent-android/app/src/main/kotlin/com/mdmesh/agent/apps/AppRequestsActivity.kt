package com.mdmesh.agent.apps

import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.text.InputFilter
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.google.android.material.bottomsheet.BottomSheetDialog
import com.google.android.material.button.MaterialButtonToggleGroup
import com.mdmesh.agent.R
import com.mdmesh.agent.ui.AgentUi
import com.mdmesh.agent.web.NetworkAccessClient
import com.mdmesh.agent.web.NetworkAdminNotificationService
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.sync.CheckInWorker
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject

class AppRequestsActivity : AppCompatActivity() {
    private lateinit var client: NetworkAccessClient
    private var admin = false
    private var busy = false
    private var message = ""
    private var rows = emptyList<JSONObject>()
    private var results = emptyList<JSONObject>()
    private var query = ""
    private var reason = ""
    private var selected: JSONObject? = null
    private var sheet: BottomSheetDialog? = null
    private var page = 1
    private var pendingShare: String? = null
    private val labels = mapOf("pending" to "Chờ duyệt", "approved" to "Đã duyệt", "rejected" to "Từ chối", "revoked" to "Đã thu hồi", "stale" to "Cấu hình đã thay đổi")
    private fun dp(n: Int) = AgentUi.dp(this,n)
    private fun column() = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
    private fun text(value: String, size: Float=15f) = TextView(this).apply { text=value;textSize=size;setPadding(0,dp(8),0,dp(8)) }
    private fun button(title: String, primary: Boolean=false, action: () -> Unit) = AgentUi.button(this,title,primary).apply { isEnabled=!busy;setOnClickListener { action() } }
    private fun array(value: JSONArray?) = value?.let { a -> (0 until a.length()).map { a.getJSONObject(it) } } ?: emptyList()
    private suspend fun path() = "/public/agent/v1/app-requests/${DeviceIdStore(this).current() ?: error("Máy chưa đăng ký. Mở trang chính của Agent để đăng ký trước.")}"
    override fun onCreate(state: Bundle?) {
        super.onCreate(state);client=NetworkAccessClient(applicationContext)
        admin=state?.getBoolean("admin") ?: intent.getBooleanExtra("adminInbox",false)
        query=state?.getString("query").orEmpty();reason=state?.getString("reason").orEmpty()
        selected=state?.getString("selected")?.let { JSONObject(it) }
        render();NetworkAdminNotificationService.start(this)
        val shared=intent.getStringExtra("sharedPackage")
        if(selected!=null) form(selected!!)
        else if(shared!=null) acceptShare(shared)
        else refresh()
        lifecycleScope.launch {repeatOnLifecycle(Lifecycle.State.RESUMED){while(true){delay(30000);if(!busy&&sheet==null)refresh()}}}
    }
    override fun onSaveInstanceState(out: Bundle) {super.onSaveInstanceState(out);out.putBoolean("admin",admin);out.putString("query",query);out.putString("reason",reason);out.putString("selected",selected?.toString())}
    override fun onNewIntent(intent: Intent) {super.onNewIntent(intent);setIntent(intent);intent.getStringExtra("sharedPackage")?.let {acceptShare(it)}}
    override fun onRestart(){super.onRestart();if(!client.isAdmin()&&admin){rows=emptyList();admin=false};selected?.let{form(it)};refresh()}
    override fun onStop(){sheet?.dismiss();super.onStop()}
    private fun perform(block: suspend () -> Unit) {
        if(busy)return
        busy=true;render()
        lifecycleScope.launch {try{block()}catch(e:Exception){if(e is CancellationException)throw e;message=e.message ?: "Không thực hiện được";Toast.makeText(this@AppRequestsActivity,message,Toast.LENGTH_LONG).show()}
            finally{busy=false;if(!isFinishing){render();pendingShare?.let{pendingShare=null;acceptShare(it)}}}}
    }
    private suspend fun load(){
        if(admin){check(client.isAdmin()){ "Đăng nhập từ Tài khoản quản trị để duyệt yêu cầu." };rows=array(client.admin("/private/app-requests?page=$page").optJSONArray("data"))}
        else {
            val base=path();rows=array(client.device(base).optJSONArray("data"))
            var changed=false
            for(row in rows.filter { it.optString("status")=="approved" && it.isNull("installedAt") }) {
                val pkg=row.getString("packageName")
                if(runCatching{packageManager.getPackageInfo(pkg,0)}.isSuccess){client.device("$base/${row.getInt("id")}/installed",JSONObject());changed=true}
            }
            if(changed)rows=array(client.device(base).optJSONArray("data"))
            if(rows.any{it.optString("status")=="approved"})CheckInWorker.scheduleNow(this)
        }
    }
    private fun refresh()=perform {load();message="Đã làm mới"}
    private fun search()=perform {
        check(query.trim().length in 2..200){"Nhập từ 2 đến 200 ký tự."}
        results=array(client.device(path()+"/search?q="+Uri.encode(query.trim())).optJSONArray("data"))
        message=if(results.isEmpty())"Không có kết quả. Bạn có thể tìm trên Google Play rồi chia sẻ link sang Agent." else "Chọn ứng dụng để gửi yêu cầu"
    }
    private fun acceptShare(pkg: String) {
        if(!PlayAppLink.validPackage(pkg))return
        if(busy){pendingShare=pkg;return}
        sheet?.dismiss();admin=false;rows=emptyList();results=emptyList();reason=""
        intent.removeExtra("sharedPackage")
        perform {
            val fallback=JSONObject().put("packageName",pkg).put("name",pkg).put("summary", "Chưa lấy được tên từ Google Play")
            val found=try{array(client.device(path()+"/search?q="+Uri.encode(pkg)).optJSONArray("data")).firstOrNull {it.optString("packageName")==pkg}}catch(e:Exception){if(e is CancellationException)throw e;null}
            form(found ?: fallback)
        }
    }
    private fun appRow(name: String, developer: String, pkg: String, onClick: (() -> Unit)?=null): LinearLayout {
        val row=LinearLayout(this).apply {orientation=LinearLayout.HORIZONTAL;gravity=android.view.Gravity.CENTER_VERTICAL;minimumHeight=dp(64);setPadding(dp(4),dp(8),dp(4),dp(8))}
        val icon=ImageView(this).apply{setImageDrawable(runCatching{packageManager.getApplicationIcon(pkg)}.getOrDefault(packageManager.defaultActivityIcon));importantForAccessibility=android.view.View.IMPORTANT_FOR_ACCESSIBILITY_NO}
        row.addView(icon,LinearLayout.LayoutParams(dp(36),dp(36)).apply{marginEnd=dp(12)})
        row.addView(column().apply{addView(text(name,16f).apply{setPadding(0,0,0,0)});if(developer.isNotBlank())addView(text(developer,13f).apply{setPadding(0,dp(4),0,0)})},LinearLayout.LayoutParams(0,-2,1f))
        if(onClick!=null){row.isFocusable=true;row.isClickable=true;val value=android.util.TypedValue();theme.resolveAttribute(android.R.attr.selectableItemBackground,value,true);row.setBackgroundResource(value.resourceId);row.setOnClickListener{if(!busy)onClick()}}
        return row
    }
    private fun render() {
        if(admin||client.isAdmin())window.addFlags(WindowManager.LayoutParams.FLAG_SECURE) else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        val root=column().apply {setBackgroundColor(getColor(R.color.network_background))}
        root.addView(AgentUi.toolbar(this,"Yêu cầu cài ứng dụng"))
        val roles=MaterialButtonToggleGroup(this).apply{isSingleSelection=true;isSelectionRequired=true}
        val personal=button("Của thiết bị"){admin=false;page=1;rows=emptyList();refresh()}.apply{id=android.view.View.generateViewId()}
        val manage=button("Quản trị"){admin=true;page=1;rows=emptyList();refresh()}.apply{id=android.view.View.generateViewId()}
        roles.addView(personal,LinearLayout.LayoutParams(0,dp(48),1f));roles.addView(manage,LinearLayout.LayoutParams(0,dp(48),1f));roles.check(if(admin)manage.id else personal.id);root.addView(roles)
        val body=column();root.addView(ScrollView(this).apply{addView(body)},LinearLayout.LayoutParams(-1,0,1f))
        if(!admin){
            body.addView(text("Tìm từ danh mục Google Play qua MDMesh. Kết quả chưa xác nhận tương thích với máy này; Google Play trên máy sẽ kiểm tra khi cài."))
            val input=EditText(this).apply{setSingleLine();hint="Tên ứng dụng";setText(query);filters=arrayOf(InputFilter.LengthFilter(200))}
            input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){query=s.toString()};override fun afterTextChanged(s:android.text.Editable?){}})
            body.addView(input);body.addView(button("Tìm ứng dụng",true){search()})
            body.addView(button("Tìm trong Google Play"){openPlay()})
            body.addView(text("Trong Google Play: mở trang ứng dụng → Chia sẻ → MDMesh · Yêu cầu cài app."))
            for(app in results)body.addView(appRow(app.optString("name"),app.optString("summary"),app.optString("packageName")){reason="";form(app)})
        }
        body.addView(text(if(busy)"Đang xử lý…" else message))
        body.addView(button("Làm mới"){refresh()})
        if(admin&&!client.isAdmin())body.addView(text("Mở biểu tượng tài khoản trên thanh trên để đăng nhập quản trị."))
        if(admin){body.addView(button("Trang trước"){if(page>1){page--;refresh()}}.apply{isEnabled=!busy&&page>1});body.addView(text("Trang $page"));body.addView(button("Trang sau"){page++;refresh()}.apply{isEnabled=!busy&&rows.size==100})}
        if(rows.isEmpty())body.addView(text("Chưa có yêu cầu."))
        for(row in rows){
            val status=row.optString("status")
            val card=column().apply{setPadding(0,dp(8),0,dp(12))}
            val heading=if(admin)row.optString("deviceName",row.optString("deviceNumber"))+" · " else ""
            card.addView(appRow(heading+row.optString("appName")+(if(row.optString("appName")==row.optString("packageName"))" · Chưa lấy được tên" else ""),row.optString("developer").takeUnless{it=="null"}.orEmpty(),row.optString("packageName")));card.addView(text(labels[status] ?: status))
            card.addView(text(row.optString("reason")))
            card.addView(button("Chi tiết"){details(row)})
            if(!admin&&status=="approved")card.addView(button("Mở trong Google Play",true){openApproved(row)})
            if(!row.isNull("installedAt"))card.addView(text("Thiết bị đã báo cài đặt"))
            body.addView(card)
        }
        setContentView(root);AgentUi.insets(this,root)
    }
    private fun showSheet(title: String, build: (LinearLayout,BottomSheetDialog)->Unit){
        sheet?.dismiss()
        val dialog=BottomSheetDialog(this);val body=column().apply{setPadding(dp(20),dp(12),dp(20),dp(24));addView(text(title,21f))}
        build(body,dialog);dialog.setContentView(ScrollView(this).apply{addView(body)})
        if(admin||client.isAdmin())dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        sheet=dialog;dialog.setOnDismissListener{if(sheet===dialog)sheet=null};dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        androidx.core.view.ViewCompat.setOnApplyWindowInsetsListener(body){v,insets->val bars=insets.getInsets(androidx.core.view.WindowInsetsCompat.Type.systemBars() or androidx.core.view.WindowInsetsCompat.Type.ime());v.setPadding(dp(20),dp(12),dp(20),dp(24)+bars.bottom);insets}
    }
    private fun form(app: JSONObject){
        selected=app
        showSheet("Yêu cầu cài "+app.optString("name")){body,dialog->
            body.addView(text(app.optString("summary")))
            body.addView(text(app.getString("packageName")))
            body.addView(text("Chỉ gửi yêu cầu; chưa tải hoặc cài ứng dụng."))
            val input=EditText(this).apply{hint="Lý do cần cài ứng dụng";setText(reason);minLines=2;filters=arrayOf(InputFilter.LengthFilter(1000))};body.addView(input)
            input.addTextChangedListener(object:android.text.TextWatcher{override fun beforeTextChanged(s:CharSequence?,start:Int,count:Int,after:Int){};override fun onTextChanged(s:CharSequence?,start:Int,before:Int,count:Int){reason=s.toString()};override fun afterTextChanged(s:android.text.Editable?){}})
            body.addView(button("Gửi yêu cầu",true){
                if(reason.isBlank()){input.error="Nhập lý do";return@button}
                perform{client.device(path(),JSONObject().put("packageName",app.getString("packageName")).put("appName",app.getString("name").take(200)).put("developer",app.optString("summary").take(300)).put("reason",reason.trim()));selected=null;reason="";dialog.dismiss();load();message="Đã gửi yêu cầu"}
            }.apply{isEnabled=true})
            body.addView(button("Hủy"){selected=null;reason="";dialog.dismiss()}.apply{isEnabled=true})
        }
    }
    private fun details(row: JSONObject){
        showSheet(row.optString("appName")){body,dialog->
            body.addView(text(row.optString("developer")));body.addView(text(row.getString("packageName")))
            body.addView(text(row.optString("reason")));body.addView(text(labels[row.optString("status")] ?: row.optString("status")))
            if(!row.isNull("decisionBy"))body.addView(text(row.optString("decisionBy")+": "+row.optString("decisionNote","")))
            body.addView(text("Phê duyệt mở trang cài đúng app. Google Play thông thường vẫn có thể cho phép tìm và cài ứng dụng khác. Chính sách kiosk, mạng và quyền cài trên máy vẫn áp dụng."))
            if(admin&&client.isAdmin()&&row.optString("status") in setOf("pending","approved")){
                val note=EditText(this).apply{hint="Ghi chú quản trị";filters=arrayOf(InputFilter.LengthFilter(1000))};body.addView(note)
                val actions=if(row.getString("status")=="pending")listOf("approve" to "Duyệt", "reject" to "Từ chối") else listOf("revoke" to "Thu hồi")
                for((action,title) in actions)body.addView(button(title,action=="approve"){
                    perform{client.admin("/private/app-requests/${row.getInt("id")}/decision",JSONObject().put("action",action).put("note",note.text.toString()));dialog.dismiss();load();message="Đã lưu quyết định"}
                })
            }
            if(admin&&client.isAdmin()){
                val history=text("Đang tải lịch sử…");body.addView(history)
                lifecycleScope.launch{try{val all=array(client.admin("/private/app-requests/${row.getInt("id")}/history").optJSONArray("data"));history.text=all.joinToString("\n"){(labels[it.optString("status")] ?: it.optString("status"))+" · "+it.optString("actor")+" · "+it.optString("note","")}}catch(e:Exception){if(e is CancellationException)throw e;history.text="Không tải được lịch sử"}}
            }
        }
    }
    private fun openApproved(row: JSONObject)=perform {
        // Revalidate with server: never open from an old approval after revocation/offline.
        val current=array(client.device(path()).optJSONArray("data")).firstOrNull{it.optInt("id")==row.optInt("id")}
        check(current?.optString("status")=="approved"){"Quyền đã thay đổi. Hãy làm mới yêu cầu."}
        CheckInWorker.scheduleNow(this)
        openPlay(row.getString("packageName"))
    }
    private fun openPlay(pkg: String?=null){
        if(pkg!=null&&!PlayAppLink.validPackage(pkg))return
        val uri=if(pkg==null)Uri.parse("market://search?q=apps&c=apps") else Uri.parse("market://details?id="+Uri.encode(pkg))
        try{startActivity(Intent(Intent.ACTION_VIEW,uri).setPackage("com.android.vending"))}
        catch(e:Exception){message="Không mở được Google Play. Kiểm tra Google Play đã có trên máy và được chính sách thiết bị cho phép.";Toast.makeText(this,message,Toast.LENGTH_LONG).show()}
    }
    override fun onDestroy(){sheet?.dismiss();super.onDestroy()}
}
