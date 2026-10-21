package com.mdmesh.agent.web

import com.google.android.material.dialog.MaterialAlertDialogBuilder
import com.google.android.material.button.MaterialButton
import com.mdmesh.agent.R
import android.content.Intent
import android.content.res.ColorStateList
import android.view.Gravity
import android.view.MenuItem
import com.google.android.material.appbar.MaterialToolbar
import com.google.android.material.button.MaterialButtonToggleGroup
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.doOnAttach
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowCompat
import com.google.android.material.bottomsheet.BottomSheetDialog
import android.view.View
import android.view.ViewGroup
import android.Manifest
import android.os.Build
import androidx.activity.result.contract.ActivityResultContracts
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.mdmesh.core.sync.CheckInWorker
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NetworkAccessActivity : AppCompatActivity() {
    private lateinit var client: NetworkAccessClient
    private var status = ""
    private var adminMode = false
    private var selectedTab = 0
    private var page = 1
    private var librarySession=""
    private val appNames=mutableMapOf<String,String>()
    private lateinit var rowsView: LinearLayout
    private var scrollY = 0
    private var listScroll: ScrollView? = null
    private var activeSheet: BottomSheetDialog? = null
    private fun dp(value: Int) = (value * resources.displayMetrics.density).toInt()
    private fun actionButton(title:String,primary:Boolean=true)=com.mdmesh.agent.ui.AgentUi.button(this,title,primary)
    private fun sheet(title: String, secure: Boolean = false, build: (LinearLayout, BottomSheetDialog) -> Unit) {
        val dialog = BottomSheetDialog(this)
        activeSheet?.dismiss(); activeSheet=dialog
        dialog.setOnDismissListener { if(activeSheet===dialog)activeSheet=null }
        val body = LinearLayout(this).apply { setBackgroundColor(getColor(R.color.network_surface)); orientation = LinearLayout.VERTICAL; setPadding(dp(20),dp(16),dp(20),dp(24)) }
        body.addView(TextView(this).apply { text=title; textSize=21f; setPadding(0,0,0,dp(16)) })
        build(body,dialog)
        dialog.setContentView(ScrollView(this).apply { addView(body) })
        if (secure || adminMode) dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        dialog.show()
        dialog.window?.setSoftInputMode(WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        ViewCompat.setOnApplyWindowInsetsListener(body) { view, insets ->
            val edge=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.ime())
            view.setPadding(dp(20),dp(16),dp(20),dp(24)+edge.bottom); insets
        }
    }
    private fun account() { startActivity(Intent(this,AdminAccountActivity::class.java)) }

    private var adminRows: List<JSONObject> = emptyList()
    private var busy = false
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        client = NetworkAccessClient(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window,false)
        adminMode=intent.getBooleanExtra("adminInbox",false)
        render()
        NetworkAdminNotificationService.start(this)
        refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { delay(30_000); if (!busy && activeSheet==null) refresh() }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        activeSheet?.dismiss()

    }
    override fun onNewIntent(intent:Intent) { super.onNewIntent(intent);setIntent(intent);if(intent.getBooleanExtra("adminInbox",false)){adminMode=true;selectedTab=0;render()} }
    override fun onRestart() { super.onRestart();if(!client.isAdmin())adminRows=emptyList();render();refresh() }
    private fun label(pkg: String) = if (pkg.isBlank()) "Chưa xác định ứng dụng" else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(appNames[pkg] ?: "$pkg · Chưa có trong thư viện")
    private fun time(value: Long) = Instant.ofEpochMilli(value).atZone(ZoneId.systemDefault()).format(DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy"))
    private fun perform(block: suspend () -> Unit) {
        if (busy) return
        busy = true; render()
        lifecycleScope.launch {
            try { block() } catch (e: Exception) { if (e is CancellationException) throw e; status = e.message ?: "Không thực hiện được" }
            finally { busy = false; if (!isFinishing) render() }
        }
    }
    private fun refresh() = perform {
        if (NetworkAccessStore.outbox(this).isNotEmpty()) NetworkRequestWorker.enqueue(this)
        runCatching { client.device(client.devicePath()) }.onSuccess { result -> result.optJSONArray("data")?.let { NetworkAccessStore.requests(this,it) } }.onFailure { if (!adminMode) status=it.message.orEmpty() }
        if(!client.isAdmin())adminRows=emptyList()
        if (client.isAdmin()) {
            val all = client.admin("/private/network-access/requests?page=$page").getJSONArray("data")
            adminRows = (0 until all.length()).map { all.getJSONObject(it) }
            val key=AdminSessionStore.get(this).snapshot().cookie
            if(key!=librarySession) {
                appNames.clear()
                runCatching {client.admin("/private/applications/search").optJSONArray("data")}.getOrNull()?.let {apps ->
                    for(i in 0 until apps.length()){val app=apps.getJSONObject(i);appNames[app.optString("pkg")]=app.optString("name",app.optString("pkg"))}
                }
                librarySession=key
            }
        }
        CheckInWorker.scheduleNow(this)
        status = "Đã đồng bộ · ${DateTimeFormatter.ofPattern("HH:mm").format(java.time.LocalTime.now())}"
    }
    private fun render() {
        if(!client.isAdmin()){adminRows=emptyList();appNames.clear();librarySession=""}
        // Protect credentials and the admin inbox, while permitting support captures
        // of the personal screen without account/session information.
        if(adminMode || client.isAdmin())window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_SECURE)
        scrollY=listScroll?.scrollY ?: 0
        val root=LinearLayout(this).apply { setBackgroundColor(getColor(R.color.network_background)); orientation=LinearLayout.VERTICAL; setPadding(dp(16),0,dp(16),0) }
        ViewCompat.setOnApplyWindowInsetsListener(root) { v,insets ->
            val edges=insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
            v.setPadding(dp(16)+edges.left,edges.top,dp(16)+edges.right,edges.bottom); insets
        }
        fun button(parent:LinearLayout,title:String, click:()->Unit) {
            parent.addView(actionButton(title, primary=false).apply { isEnabled=!busy; setOnClickListener { click() } })
        }
        val toolbar=MaterialToolbar(this).apply {
            title="Truy cập mạng"
            setTitleTextColor(getColor(R.color.network_on_surface))
            setBackgroundColor(getColor(R.color.network_background))
            setNavigationIcon(R.drawable.network_back)
            navigationContentDescription="Quay lại"
            setNavigationIconTint(getColor(R.color.network_on_surface))
            setNavigationOnClickListener { finish() }
            menu.add(0,1,0,"Làm mới").apply {
                setIcon(R.drawable.network_refresh)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
                isEnabled=!busy
            }
            menu.add(0,2,1,"Tài khoản quản trị").apply {
                setIcon(R.drawable.network_account)
                setShowAsAction(MenuItem.SHOW_AS_ACTION_ALWAYS)
            }
            setOnMenuItemClickListener { item ->
                when(item.itemId){1->refresh();2->account()};true
            }
        }
        root.addView(toolbar,LinearLayout.LayoutParams(-1,dp(56)))
        val modes=MaterialButtonToggleGroup(this).apply { isSingleSelection=true;isSelectionRequired=true }
        val selectedStates=arrayOf(intArrayOf(android.R.attr.state_checked),intArrayOf())
        val personalId=View.generateViewId();val adminId=View.generateViewId()
        listOf(personalId to "Cá nhân",adminId to "Quản trị").forEach { (viewId,label) ->
            modes.addView(MaterialButton(this,null,com.google.android.material.R.attr.materialButtonOutlinedStyle).apply {
                id=viewId;text=label;isAllCaps=false;textSize=14f;cornerRadius=dp(12)
                minimumHeight=dp(48);insetTop=dp(4);insetBottom=dp(4)
                backgroundTintList=ColorStateList(selectedStates,intArrayOf(0xFFE5EFFF.toInt(),getColor(R.color.network_surface)))
                setTextColor(ColorStateList(selectedStates,intArrayOf(getColor(R.color.network_primary),getColor(R.color.network_secondary_text))))
                strokeColor=ColorStateList.valueOf(getColor(R.color.network_outline))
                icon=getDrawable(R.drawable.network_check);iconSize=dp(18)
                iconTint=ColorStateList(selectedStates,intArrayOf(getColor(R.color.network_primary),android.graphics.Color.TRANSPARENT))
            },LinearLayout.LayoutParams(0,dp(48),1f))
        }
        modes.check(if(adminMode)adminId else personalId)
        modes.addOnButtonCheckedListener { _, checkedId, checked ->
            if(checked){
                val nextAdmin=checkedId==adminId
                if(nextAdmin!=adminMode){adminMode=nextAdmin;selectedTab=0;page=1;listScroll=null;render();if(adminMode){if(!client.isAdmin())login() else refresh()}}
            }
        }
        root.addView(modes,LinearLayout.LayoutParams(-1,dp(48)).apply { topMargin=dp(8);bottomMargin=dp(8) })
        val tabs=com.google.android.material.tabs.TabLayout(this).apply {
            setBackgroundColor(getColor(R.color.network_background))
            setTabTextColors(getColor(R.color.network_secondary_text),getColor(R.color.network_primary))
            setSelectedTabIndicatorColor(getColor(R.color.network_primary))
        }
        val names=if(adminMode)listOf("Chờ duyệt","Đang mở","Lịch sử") else listOf("Bị chặn","Yêu cầu của tôi")
        names.forEach { tabs.addTab(tabs.newTab().setText(it)) }; tabs.getTabAt(selectedTab)?.select()
        tabs.addOnTabSelectedListener(object:com.google.android.material.tabs.TabLayout.OnTabSelectedListener {
            override fun onTabSelected(tab:com.google.android.material.tabs.TabLayout.Tab){selectedTab=tab.position;render()}
            override fun onTabUnselected(tab:com.google.android.material.tabs.TabLayout.Tab){}
            override fun onTabReselected(tab:com.google.android.material.tabs.TabLayout.Tab){}
        });root.addView(tabs)
        if(status.isNotBlank())root.addView(TextView(this).apply { text=status;textSize=12f;setPadding(0,dp(8),0,dp(8));maxLines=3 })
        rowsView=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL }
        val scroll=ScrollView(this).apply { addView(rowsView);isFillViewport=true }
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        val policy=WebFilterConfig.load(this)
        val rows=if(adminMode) adminRows.filter { val state=it.optString("effectiveStatus",it.optString("status")); when(selectedTab){0->state=="pending";1->state=="approved";else->state !in setOf("pending","approved")} }
            else if(selectedTab==0)NetworkAccessStore.history(this) else NetworkAccessStore.requests(this)
        if(adminMode&&!client.isAdmin())button(rowsView,"Đăng nhập để xem yêu cầu") { login() }
        else if(rows.isEmpty())rowsView.addView(TextView(this).apply { text="Chưa có mục nào";gravity=android.view.Gravity.CENTER;setPadding(0,dp(40),0,0) })
        rows.forEach { row ->
            val event=!adminMode&&selectedTab==0
            val card=com.google.android.material.card.MaterialCardView(this).apply { radius=dp(16).toFloat();setCardBackgroundColor(getColor(R.color.network_surface));strokeColor=getColor(R.color.network_outline);strokeWidth=dp(1);isClickable=true;isFocusable=true }
            val content=LinearLayout(this).apply { gravity=android.view.Gravity.CENTER_VERTICAL;setPadding(dp(12),dp(12),dp(12),dp(12)) }
            val pkg=row.optString("packageName")
            content.addView(ImageView(this).apply { setImageDrawable(runCatching { packageManager.getApplicationIcon(pkg) }.getOrElse { getDrawable(android.R.drawable.sym_def_app_icon) });contentDescription=label(pkg) },LinearLayout.LayoutParams(dp(40),dp(40)))
            content.addView(TextView(this).apply {
                text="${label(pkg)}\n${row.optString("domain")}\n${if(event) "${row.optLong("count")} lượt · ${time(row.optLong("lastAt"))}" else if(adminMode) "${com.mdmesh.agent.ui.requestDeviceLabel(row.optString("deviceName"),row.optString("deviceNumber"))} · ${adminState(row)}" else requestState(row,policy)}"
                textSize=14f;setPadding(dp(12),0,0,0);maxLines=4;ellipsize=android.text.TextUtils.TruncateAt.END
            },LinearLayout.LayoutParams(0,-2,1f));card.addView(content)
            rowsView.addView(card,LinearLayout.LayoutParams(-1,-2).apply { bottomMargin=dp(8) })
            card.setOnClickListener { details(row,event) }
        }
        if(adminMode&&client.isAdmin()) {
            val pages=LinearLayout(this)
            if(page>1)button(pages,"Trang trước") { page--;refresh() }
            if(adminRows.size>=500)button(pages,"Trang tiếp") { page++;refresh() }
            root.addView(pages)
        }
        root.doOnAttach { ViewCompat.requestApplyInsets(it) }
        setContentView(root)
        listScroll=scroll
        scroll.post { scroll.scrollTo(0,scrollY) }
    }
    private fun adminState(row:JSONObject):String = when(row.optString("effectiveStatus",row.optString("status"))) {
        "pending"->"Chờ duyệt"
        "approved"->if(!row.optBoolean("supported"))"Cần cập nhật Agent" else if(row.optBoolean("applied"))"Đã áp dụng" else "Đã duyệt · chờ thiết bị đồng bộ"
        "revoked"->"Đã thu hồi";"rejected"->"Đã từ chối";"stale"->"Chính sách đã thay đổi";"expired"->"Đã hết hạn";else->"Chưa xác định"
    }
    private fun details(row:JSONObject,event:Boolean)=sheet(row.optString("domain")) { body,dialog ->
        if (!event) com.mdmesh.agent.ui.AgentUi.requestDevice(body, row)
        body.addView(TextView(this).apply { text="${label(row.optString("packageName"))}\n${if(event) "${row.optLong("count")} lượt · ${time(row.optLong("lastAt"))}" else if(adminMode) adminState(row) else requestState(row,WebFilterConfig.load(this@NetworkAccessActivity))}\n${row.optString("reason")}\n${row.optString("decisionNote")}";textSize=16f })
        fun action(title:String,primary:Boolean=true,click:()->Unit){body.addView(actionButton(title,primary).apply{setOnClickListener{dialog.dismiss();click()}})}
        if(event){
            val current=row.optString("policyHash").isNotBlank()&&row.optString("policyHash")==WebFilterConfig.load(this).policyHash
            val pending=NetworkAccessStore.requests(this).any { it.optString("domain")==row.optString("domain")&&it.optString("packageName")==row.optString("packageName")&&it.optString("status")=="pending" }
            val queued=NetworkAccessStore.outbox(this).any { it.optString("key")==row.optString("key") }
            if(current&&!pending&&!queued)action("Yêu cầu mở truy cập"){request(row)}
            else body.addView(TextView(this).apply{text=if(!current)"Sự kiện thuộc chính sách cũ. Làm mới và thử truy cập lại để ghi nhận sự kiện mới." else if(pending)"Đang chờ admin duyệt" else "Đã lưu yêu cầu · đang chờ gửi"})
        }else if(adminMode&&client.isAdmin())when(row.optString("status")){
            "pending"->{action("Duyệt yêu cầu"){decision(row)};action("Từ chối",primary=false){decisionNote(row,"reject")}}
            "approved"->action("Thu hồi ngoại lệ",primary=false){decisionNote(row,"revoke")}
        }
    }
    private fun requestState(row: JSONObject, policy: WebFilterPolicy): String = when(row.optString("status")) {
        "pending" -> "Chờ admin duyệt"
        "rejected" -> "Đã từ chối · ${row.optString("decisionNote")}"
        "revoked" -> when (networkRevocationState(row.optInt("id"), WebFilterRuntime.applied)) {
            NetworkRevocationState.REMOVED -> "Đã áp dụng thu hồi ngoại lệ trên thiết bị"
            NetworkRevocationState.PENDING -> "Admin đã thu hồi · đang chờ thiết bị gỡ ngoại lệ"
            NetworkRevocationState.UNVERIFIED -> "Admin đã thu hồi · chưa xác minh được bộ lọc đang chạy"
        }
        "approved" -> {
            val expiry = if (row.isNull("expiresAt")) null else row.optLong("expiresAt")
            when {
                row.optString("policyHash") != policy.policyHash -> "Chính sách đã thay đổi · ngoại lệ không còn áp dụng"
                expiry != null && expiry <= System.currentTimeMillis() -> "Ngoại lệ đã hết hạn"
                WebFilterRuntime.applied?.grants?.any { it.requestId==row.optInt("id") } == true -> "Đã áp dụng · ${expiry?.let { "đến ${time(it)}" } ?: "vô thời hạn, đến khi thu hồi"}"
                else -> "Admin đã duyệt · đang chờ thiết bị áp dụng"
            }
        }
        else -> row.optString("status")
    }
    private fun request(event: JSONObject) = sheet("Yêu cầu mở truy cập") { body,dialog ->
        body.addView(TextView(this).apply { text="${label(event.optString("packageName"))} · ${event.optString("domain")}" })
        val input=EditText(this).apply { hint="Lý do cần mở truy cập";maxLines=5;filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) };body.addView(input)
        body.addView(actionButton("Gửi yêu cầu").apply { setOnClickListener {
            val reason=input.text.toString().trim()
            if(reason.isEmpty())input.error="Nhập lý do" else try { NetworkAccessStore.enqueue(this@NetworkAccessActivity,event,reason);NetworkRequestWorker.enqueue(this@NetworkAccessActivity);status="Đã lưu yêu cầu · sẽ gửi khi có mạng";dialog.dismiss();render() }catch(e:Exception){input.error=e.message}
        } })
    }
    private fun login() { startActivity(Intent(this,AdminAccountActivity::class.java)) }
    private fun decision(row: JSONObject) {
        perform {
            val config=runCatching { client.admin("/private/network-access/configuration/${row.getInt("configurationId")}").optJSONArray("data") }.getOrNull() ?: JSONArray()
            sheet("Mở ${row.optString("domain")}") { body,dialog ->
                com.mdmesh.agent.ui.AgentUi.requestDevice(body, row)
                val pkg=row.optString("packageName")
                val scopes=mutableListOf<Pair<String,String>>()
                if(pkg.isNotBlank())scopes+="APP" to "Ứng dụng: ${label(pkg)}" else scopes+="" to "Chọn phạm vi"
                for(i in 0 until config.length()) {
                    val rule=config.getJSONObject(i);val packages=rule.optJSONArray("packages") ?: JSONArray()
                    if(rule.optString("scope")=="SELECTED"&&(0 until packages.length()).any { packages.optString(it)==pkg }&&pkg.isNotBlank())scopes+="GROUP:${rule.getString("id")}" to "Nhóm: ${rule.optString("name")}"
                }
                scopes+="ALL" to "Tất cả ứng dụng trên thiết bị này"
                val scope=Spinner(this).apply { adapter=ArrayAdapter(this@NetworkAccessActivity,android.R.layout.simple_spinner_dropdown_item,scopes.map{it.second}) };body.addView(scope)
                val durations=listOf("15M" to "15 phút","1H" to "1 giờ","TODAY" to "Cuối ngày (${ZoneId.systemDefault()})","FOREVER" to "Vô thời hạn · đến khi thu hồi")
                val duration=Spinner(this).apply { adapter=ArrayAdapter(this@NetworkAccessActivity,android.R.layout.simple_spinner_dropdown_item,durations.map{it.second}) };body.addView(duration)
                val note=EditText(this).apply { hint="Ghi chú";filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) };body.addView(note)
                body.addView(TextView(this).apply { text="Chỉ mở đúng hostname trên thiết bị gửi yêu cầu. Phạm vi tất cả ứng dụng sẽ mở rộng hơn ứng dụng gửi yêu cầu." })
                body.addView(actionButton("Duyệt yêu cầu").apply { setOnClickListener {
                    val selected=scopes[scope.selectedItemPosition].first
                    if(selected.isEmpty()){note.error="Chọn phạm vi";return@setOnClickListener}
                    isEnabled=false
                    lifecycleScope.launch { try {
                        client.admin("/private/network-access/requests/${row.getInt("id")}/decision",JSONObject().put("action","approve").put("scope",selected.substringBefore(':')).put("ruleId",selected.substringAfter(':',"")).put("duration",durations[duration.selectedItemPosition].first).put("timezone",ZoneId.systemDefault().id).put("note",note.text.toString()))
                        dialog.dismiss();refresh()
                    }catch(e:Exception){if(e is CancellationException)throw e;note.error=e.message;isEnabled=true} }
                } })
            }
        }
    }
    private fun decisionNote(row:JSONObject, action:String) {
        val note=EditText(this).apply { hint="Ghi chú";filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        MaterialAlertDialogBuilder(this).setTitle(if(action=="reject")"Từ chối yêu cầu" else "Thu hồi ngoại lệ").setMessage(com.mdmesh.agent.ui.AgentUi.requestDeviceText(row)).setView(note).setNegativeButton("Hủy",null).setPositiveButton("Xác nhận") { _,_->submit(row,JSONObject().put("action",action).put("note",note.text.toString())) }.show()
    }
    private fun submit(row:JSONObject, payload:JSONObject)=perform {
        client.admin("/private/network-access/requests/${row.getInt("id")}/decision",payload)
        adminRows=emptyList();CheckInWorker.scheduleNow(this)
        status="Đã lưu quyết định. Nhấn Làm mới để xem trạng thái áp dụng."
    }
}
