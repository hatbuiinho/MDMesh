package com.mdmesh.agent.web

import android.app.AlertDialog
import android.content.Intent
import android.graphics.Color
import android.os.Bundle
import android.text.InputType
import android.view.WindowManager
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.mdmesh.core.sync.CheckInWorker
import kotlinx.coroutines.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.URLEncoder
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

class NetworkAccessActivity : ComponentActivity() {
    private lateinit var client: NetworkAccessClient
    private var status = ""
    private var adminRows: List<JSONObject> = emptyList()
    private var busy = false
    private var sessionTimer: Job? = null
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
        client = NetworkAccessClient(applicationContext)
        render()
        refresh()
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) { delay(30_000); if (!busy) refresh() }
            }
        }
    }
    override fun onStop() {
        super.onStop()
        sessionTimer?.cancel()
        val cookie = client.clearSession()
        adminRows = emptyList()
        // This best-effort invalidation must outlive the activity's lifecycle cancellation.
        if (cookie.isNotEmpty()) CoroutineScope(Dispatchers.IO).launch { client.invalidate(cookie) }
    }
    override fun onRestart() { super.onRestart(); render() }
    private fun label(pkg: String) = if (pkg.isBlank()) "Chưa xác định ứng dụng" else runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault("$pkg · Không còn trên máy")
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
        val result = client.device(client.devicePath())
        result.optJSONArray("data")?.let { NetworkAccessStore.requests(this, it) }
        if (client.isAdmin()) {
            val number = client.devicePath().substringAfterLast('/')
            val all = client.admin("/private/network-access/requests?device=${URLEncoder.encode(number, "UTF-8")}").getJSONArray("data")
            adminRows = (0 until all.length()).map { all.getJSONObject(it) }
        }
        CheckInWorker.scheduleNow(this)
        status = "Đã tải trạng thái. Thiết bị đang đồng bộ chính sách."
    }
    private fun render() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32); setBackgroundColor(Color.rgb(14,17,23)) }
        fun text(value: String, size: Float = 16f) { root.addView(TextView(this).apply { text=value; textSize=size; setTextColor(Color.WHITE); setPadding(0,14,0,14) }) }
        fun button(title: String, enabled: Boolean = true, click: () -> Unit) { root.addView(Button(this).apply { text=title; isEnabled=enabled&&!busy; setOnClickListener { click() } }) }
        text("Truy cập mạng bị chặn", 24f)
        button("Đóng") { finish() }
        button(if (busy) "Đang xử lý…" else "Làm mới / đồng bộ") { refresh() }
        if (client.isAdmin()) button("Khóa phiên admin") { perform { client.logout(); adminRows=emptyList(); status="Đã khóa phiên admin" } }
        else button("Admin đăng nhập để duyệt tại máy") { login() }
        if (status.isNotEmpty()) text(status)
        val policy = WebFilterConfig.load(this)
        text("Chỉ quản lý truy cập mạng. Domain có thể đến từ kết nối nền của ứng dụng. Thay đổi áp dụng cho truy vấn mới; kết nối đang mở có thể cần đóng rồi mở lại.", 14f)
        if (client.isAdmin()) {
            text("Quản trị · phiên tối đa 5 phút", 20f)
            adminRows.filter { it.optString("status") in setOf("pending", "approved") }.forEach { row ->
                text("${label(row.optString("packageName"))}\n${row.optString("domain")}\n${row.optString("reason")}\n${requestState(row, policy)}")
                if (row.optString("status")=="pending") {
                    button("Duyệt ${row.optString("domain")}") { decision(row) }
                    button("Từ chối") { decisionNote(row, "reject") }
                } else button("Thu hồi ngoại lệ") { decisionNote(row, "revoke") }
            }
        }
        val requests = NetworkAccessStore.requests(this)
        val queued = NetworkAccessStore.outbox(this)
        if (requests.isNotEmpty()) {
            text("Yêu cầu đã gửi", 20f)
            requests.take(30).forEach { row -> text("${label(row.optString("packageName"))} · ${row.optString("domain")}\n${requestState(row, policy)}") }
        }
        NetworkAccessStore.failures(this).takeLast(5).forEach { text("Chưa gửi được ${it.optString("domain")}: ${it.optString("error")}") }
        val history = NetworkAccessStore.history(this)
        if (history.isEmpty()) text("Chưa có truy cập mạng bị chặn được ghi nhận.")
        history.groupBy { it.optString("packageName") }.forEach { (pkg, events) ->
            text(label(pkg), 20f)
            events.take(50).forEach { event ->
                val domain = event.getString("domain")
                val matching = requests.firstOrNull { it.optString("packageName")==pkg && it.optString("domain")==domain && it.optString("policyHash")==event.optString("policyHash") }
                val inQueue = queued.any { it.optString("key")==event.optString("key") }
                text("$domain\n${event.optJSONArray("rules")?.let { a -> (0 until a.length()).joinToString(", ") { a.getString(it) } }.orEmpty()}\n${time(event.optLong("lastAt"))} · ${event.optLong("count")} lượt\n${if (inQueue) "Chưa gửi · chờ mạng / gửi lại" else matching?.let { requestState(it, policy) }.orEmpty()}")
                val current = event.optString("policyHash").isNotBlank() && event.optString("policyHash")==policy.policyHash
                val pending = matching?.optString("status")=="pending"
                button(if (!current) "Sự kiện thuộc chính sách cũ / cần cập nhật server" else if (pending) "Đang chờ admin" else "Yêu cầu mở truy cập", current&&!pending&&!inQueue) { request(event) }
            }
        }
        setContentView(ScrollView(this).apply { addView(root) })
    }
    private fun requestState(row: JSONObject, policy: WebFilterPolicy): String = when(row.optString("status")) {
        "pending" -> "Chờ admin duyệt"
        "rejected" -> "Đã từ chối · ${row.optString("decisionNote")}" 
        "revoked" -> "Admin đã thu hồi · chờ đồng bộ nếu thiết bị chưa áp dụng"
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
    private fun request(event: JSONObject) {
        val input = EditText(this).apply { hint="Lý do cần mở truy cập"; maxLines=5; filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        val dialog = AlertDialog.Builder(this).setTitle("Yêu cầu mở ${event.optString("domain")}").setMessage("Ứng dụng: ${label(event.optString("packageName"))}").setView(input).setNegativeButton("Hủy",null).setPositiveButton("Gửi",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val reason=input.text.toString().trim()
            if(reason.isEmpty()){input.error="Nhập lý do";return@setOnClickListener}
            try { NetworkAccessStore.enqueue(this,event,reason);NetworkRequestWorker.enqueue(this);status="Đã lưu yêu cầu. Sẽ gửi khi có mạng.";dialog.dismiss();render() }
            catch(e:Exception){input.error=e.message}
        } };dialog.show()
    }
    private fun login() {
        val inputs=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,0,32,0) }
        val username=EditText(this).apply { hint="Tài khoản MDMesh";inputType=InputType.TYPE_CLASS_TEXT }
        val password=EditText(this).apply { hint="Mật khẩu";inputType=InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD;isSaveEnabled=false }
        inputs.addView(username);inputs.addView(password)
        val dialog=AlertDialog.Builder(this).setTitle("Admin đăng nhập").setMessage("Xác thực với server. Không lưu mật khẩu trên máy.").setView(inputs).setNegativeButton("Hủy",null).setPositiveButton("Đăng nhập",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val user=username.text.toString();val pass=password.text.toString();password.text.clear();dialog.dismiss()
            perform { client.login(user,pass);status="Đã xác thực. Nhấn Làm mới để tải yêu cầu."; sessionTimer?.cancel();sessionTimer=lifecycleScope.launch { delay(5*60_000);client.logout();adminRows=emptyList();status="Phiên admin đã hết hạn";render() } }
        } };dialog.show();dialog.window?.addFlags(WindowManager.LayoutParams.FLAG_SECURE)
    }
    private fun decision(row: JSONObject) {
        val content=LinearLayout(this).apply { orientation=LinearLayout.VERTICAL;setPadding(32,0,32,0) }
        val pkg=row.optString("packageName")
        val policy=WebFilterConfig.load(this)
        val scopes=mutableListOf<Pair<String,String>>()
        if(pkg.isNotEmpty())scopes+="APP" to "Đúng ứng dụng: ${label(pkg)}"
        policy.rules.filter { it.scope=="SELECTED"&&pkg.isNotEmpty()&&pkg in it.packages }.forEach { scopes+="GROUP:${it.id}" to "Nhóm ${it.name}: ${it.packages.joinToString { p->label(p) }}" }
        // Never auto-select a device-wide grant when the app could not be identified.
        if(pkg.isEmpty())scopes+="" to "Chọn phạm vi (chưa xác định ứng dụng)"
        scopes+="ALL" to "Tất cả ứng dụng trên máy này"
        val scope=Spinner(this).apply { adapter=ArrayAdapter(this@NetworkAccessActivity,android.R.layout.simple_spinner_dropdown_item,scopes.map { it.second }) }
        val durations=listOf("15M" to "15 phút","1H" to "1 giờ","TODAY" to "Đến cuối ngày (${ZoneId.systemDefault()})","FOREVER" to "Vô thời hạn — đến khi admin thu hồi")
        val duration=Spinner(this).apply { adapter=ArrayAdapter(this@NetworkAccessActivity,android.R.layout.simple_spinner_dropdown_item,durations.map { it.second }) }
        val note=EditText(this).apply { hint="Ghi chú";filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        content.addView(scope);content.addView(duration);content.addView(note)
        val dialog=AlertDialog.Builder(this).setTitle("Mở ${row.optString("domain")}").setMessage("Chỉ trên máy này, đúng hostname. Tất cả ứng dụng là phạm vi rộng hơn app gửi yêu cầu.").setView(content).setNegativeButton("Hủy",null).setPositiveButton("Duyệt",null).create()
        dialog.setOnShowListener { dialog.getButton(AlertDialog.BUTTON_POSITIVE).setOnClickListener {
            val selected=scopes[scope.selectedItemPosition].first
            if(selected.isEmpty()){note.error="Chọn phạm vi rõ ràng";return@setOnClickListener}
            val payload=JSONObject().put("action","approve").put("duration",durations[duration.selectedItemPosition].first).put("timezone",ZoneId.systemDefault().id).put("scope",selected.substringBefore(':')).put("ruleId",selected.substringAfter(':',"")).put("note",note.text.toString())
            dialog.dismiss();submit(row,payload)
        } };dialog.show()
    }
    private fun decisionNote(row:JSONObject, action:String) {
        val note=EditText(this).apply { hint="Ghi chú";filters=arrayOf(android.text.InputFilter.LengthFilter(1000)) }
        AlertDialog.Builder(this).setTitle(if(action=="reject")"Từ chối yêu cầu" else "Thu hồi ngoại lệ").setView(note).setNegativeButton("Hủy",null).setPositiveButton("Xác nhận") { _,_->submit(row,JSONObject().put("action",action).put("note",note.text.toString())) }.show()
    }
    private fun submit(row:JSONObject, payload:JSONObject)=perform {
        client.admin("/private/network-access/requests/${row.getInt("id")}/decision",payload)
        adminRows=emptyList();CheckInWorker.scheduleNow(this)
        status="Đã lưu quyết định. Nhấn Làm mới để xem trạng thái áp dụng."
    }
}
