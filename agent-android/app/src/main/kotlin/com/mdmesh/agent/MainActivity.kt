package com.mdmesh.agent

import android.Manifest
import android.app.admin.DevicePolicyManager
import android.content.ActivityNotFoundException
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.content.ClipData
import android.content.ClipboardManager
import android.content.res.ColorStateList
import android.widget.Toast
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import android.graphics.Typeface
import android.os.Build
import android.os.Bundle
import android.net.Uri
import android.text.InputType
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.EditText
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.content.Intent
import androidx.appcompat.app.AppCompatActivity
import com.mdmesh.agent.ui.AgentUi
import com.mdmesh.agent.web.NetworkAccessClient
import androidx.core.content.ContextCompat
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.mdmesh.agent.service.CheckInService
import com.mdmesh.agent.provisioning.UsageAccessGate
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.DeviceIdStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.sync.CheckInCoordinator
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.core.sync.SyncStatus
import com.mdmesh.policy.wifi.DpmHandle
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.text.DateFormat
import java.util.Date
import javax.inject.Inject

/** Device enrollment and management status. */
@AndroidEntryPoint
class MainActivity : AppCompatActivity() {

    @Inject lateinit var deviceIdStore: DeviceIdStore
    @Inject lateinit var dpmHandle: DpmHandle
    @Inject lateinit var serverConfig: ServerConfigStore
    @Inject lateinit var enrollTokenStore: EnrollTokenStore
    @Inject lateinit var checkInCoordinator: CheckInCoordinator
    @Inject lateinit var syncStatus: SyncStatus

    @Inject lateinit var appUsage: com.mdmesh.core.usage.AppUsageManager
    private lateinit var accountSummary: TextView
    private lateinit var restCard: TextView

    private lateinit var deviceIdValue: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDescription: TextView
    private lateinit var connectionValue: TextView
    private lateinit var scheduleSection: LinearLayout
    private lateinit var serverValue: TextView
    private lateinit var enrollButton: Button
    private var enrolling = false
    private lateinit var manualEnrollSection: LinearLayout
    private lateinit var tokenInput: EditText
    private lateinit var serverInput: EditText
    private lateinit var manualEnrollStatus: TextView
    private var waitingForUsageAccess = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        waitingForUsageAccess = savedInstanceState?.getBoolean("waiting_for_usage_access") == true
        setContentView(buildUi())
        // Grant our own POST_NOTIFICATIONS as Device Owner BEFORE starting the foreground
        // service, so the "MDMesh active" notification is visible on Android 13+.
        grantSelfNotifications()
        // NOTE: we intentionally do NOT pop the battery-optimization request dialog here. As a
        // foreground-service Device Owner we survive Doze well enough, and that full-screen system
        // dialog used to sit on top and pause the kiosk launcher, so a kiosk.enter wouldn't take
        // visible effect until the user dismissed it. There is no public Device-Owner API to grant
        // the Doze exemption silently, so the safest behaviour is to not interrupt.
        // Start the near-real-time command channel (foreground poll loop). Starting from the
        // launcher activity keeps us in the foreground-start allowance on Android 12+.
        ContextCompat.startForegroundService(this, Intent(this, CheckInService::class.java))
        refresh()
        lifecycleScope.launch {
            lifecycle.repeatOnLifecycle(androidx.lifecycle.Lifecycle.State.RESUMED) {
                while (true) { refresh(); kotlinx.coroutines.delay(5_000) }
            }
        }
    }

    /** Self-grant POST_NOTIFICATIONS (Device Owner, API 33+) so our FGS notification shows. */
    private fun grantSelfNotifications() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        runCatching {
            val dpm = dpmHandle.dpm
            if (dpm.isDeviceOwnerApp(packageName)) {
                dpm.setPermissionGrantState(
                    dpmHandle.admin,
                    packageName,
                    Manifest.permission.POST_NOTIFICATIONS,
                    DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                )
            }
        }
    }

    override fun onResume() {
        super.onResume()
        refresh()
        if (waitingForUsageAccess && UsageAccessGate.isGranted(this)) {
            waitingForUsageAccess = false
            runManualEnrollment()
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean("waiting_for_usage_access", waitingForUsageAccess)
        super.onSaveInstanceState(outState)
    }

    private fun refresh() {
        if(::accountSummary.isInitialized){val client=NetworkAccessClient(applicationContext);accountSummary.text=if(client.isAdmin())"Quản trị: ${client.accountName}" else "Quản lý thiết bị"}
        lifecycleScope.launch {
            val id = deviceIdStore.current()
            val registered = !id.isNullOrBlank()
            val managed = isDeviceOwner()
            deviceIdValue.text = id?.takeIf { it.isNotBlank() } ?: "Chưa đăng ký"
            statusTitle.text = when {
                registered && managed -> "Thiết bị đã được quản lý"
                registered -> "Cần thiết lập quyền quản lý"
                else -> "Thiết bị chưa đăng ký"
            }
            statusDescription.text = when {
                !managed -> "Liên hệ quản trị viên để thiết lập quyền quản lý thiết bị."
                registered -> "Chính sách và ứng dụng được quản lý bởi tổ chức của bạn."
                else -> "Nhập thông tin đăng ký do quản trị viên cung cấp để bắt đầu."
            }
            statusTitle.setTextColor(if (registered && managed) OK else TEXT)
            val failure = syncStatus.lastError.value
            val success = syncStatus.lastSuccessAt.value
            connectionValue.text = when {
                failure != null -> "Chưa thể kết nối máy chủ.\n${failure.message}"
                success != null -> "Kết nối thành công lúc ${DateFormat.getDateTimeInstance(DateFormat.SHORT, DateFormat.SHORT).format(Date(success))}"
                registered -> "Đang chờ kết nối máy chủ…"
                else -> "Kết nối sẽ được kiểm tra khi đăng ký."
            }
            connectionValue.setTextColor(if (failure != null) ALERT else MUTED)
            serverValue.text = serverConfig.baseUrl()
            scheduleSection.visibility = if (appUsage.currentPolicy()?.schedules?.any { it.enabled } == true) View.VISIBLE else View.GONE
            restCard.text = com.mdmesh.agent.usage.RestScheduleActivity.summary(this@MainActivity, appUsage)
            manualEnrollSection.visibility =
                if (id.isNullOrBlank() && isDeviceOwner()) View.VISIBLE else View.GONE
        }
    }

    /** ADB Device Owner setup has no QR extras, so accept a fresh server-issued token on-device. */
    private fun saveManualEnrollment() {
        if (!isDeviceOwner()) {
            manualEnrollStatus.text = "Cần thiết lập quyền quản lý thiết bị trước khi đăng ký."
            return
        }
        val token = tokenInput.text.toString().trim()
        if (!TOKEN_PATTERN.matches(token)) {
            manualEnrollStatus.text = "Nhập mã đăng ký được cung cấp trên trang Enroll."
            return
        }
        val url = serverInput.text.toString().trim().trimEnd('/')
        val parsed = runCatching { Uri.parse(url) }.getOrNull()
        if (parsed?.scheme != "https" || parsed.host.isNullOrBlank() ||
            parsed.userInfo != null || parsed.query != null || parsed.fragment != null
        ) {
            manualEnrollStatus.text = "Nhập địa chỉ máy chủ HTTPS hợp lệ."
            return
        }
        if (enrolling) return
        lifecycleScope.launch {
            if (!deviceIdStore.current().isNullOrBlank()) {
                refresh()
                return@launch
            }
            serverConfig.save(url)
            enrollTokenStore.save(token)
            tokenInput.text.clear()
            CheckInWorker.scheduleNow(this@MainActivity)
            if (UsageAccessGate.isGranted(this@MainActivity)) {
                runManualEnrollment()
            } else {
                waitingForUsageAccess = true
                manualEnrollStatus.text = "Bật quyền truy cập dữ liệu sử dụng cho MDMesh Agent, sau đó quay lại đây."
                try {
                    startActivity(UsageAccessGate.settingsIntent())
                } catch (_: ActivityNotFoundException) {
                    manualEnrollStatus.text = "Mở Cài đặt và bật quyền truy cập dữ liệu sử dụng cho MDMesh Agent."
                }
            }
        }
    }

    private fun runManualEnrollment() {
        if (enrolling) return
        enrolling = true
        enrollButton.isEnabled = false
        enrollButton.text = "Đang đăng ký…"
        manualEnrollStatus.text = "Đang kết nối với máy chủ…"
        lifecycleScope.launch {
            try {
                withContext(Dispatchers.IO) { checkInCoordinator.runOnce() }
                manualEnrollStatus.text = "Đăng ký thành công."
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                manualEnrollStatus.text = "Đăng ký thất bại: ${e.message ?: "Kiểm tra kết nối và mã đăng ký."}"
            } finally {
                enrolling = false
                enrollButton.isEnabled = true
                enrollButton.text = "Đăng ký thiết bị"
            }
            refresh()
        }
    }

    private fun isDeviceOwner(): Boolean =
        dpmHandle.dpm.isDeviceOwnerApp(packageName)

    private fun buildUi(): ScrollView {
        androidx.core.view.WindowCompat.setDecorFitsSystemWindows(window,false)
        window.statusBarColor = BACKGROUND
        window.navigationBarColor = BACKGROUND
        WindowInsetsControllerCompat(window, window.decorView).apply {
            isAppearanceLightStatusBars = true
            isAppearanceLightNavigationBars = true
        }
        val root = column().apply {
            setPadding(dp(24), dp(28), dp(24), dp(28))
            isFocusableInTouchMode = true
        }
        root.addView(AgentUi.toolbar(this,"MDMesh",back=false))
        accountSummary=text("Quản lý thiết bị",14f,MUTED).apply {setPadding(dp(16),dp(4),0,dp(24))}
        root.addView(accountSummary)

        root.addView(card().apply {
            addView(text("TRẠNG THÁI THIẾT BỊ", 11f, MUTED, bold = true))
            statusTitle = text("Đang kiểm tra thiết bị…", 23f, TEXT, bold = true).apply { setPadding(0, dp(12), 0, dp(8)) }
            addView(statusTitle)
            statusDescription = text("", 15f, MUTED)
            addView(statusDescription)
            addView(divider())
            connectionValue = text("Đang kiểm tra kết nối…", 14f, MUTED)
            addView(connectionValue)
        })

        manualEnrollSection = card().apply {
            visibility = View.GONE
            addView(text("Đăng ký thiết bị", 20f, TEXT, bold = true))
            addView(text("Dùng mã đăng ký và địa chỉ máy chủ do quản trị viên cung cấp.", 14f, MUTED).apply { setPadding(0, dp(8), 0, dp(20)) })
            addView(text("Địa chỉ máy chủ", 13f, TEXT, bold = true))
            serverInput = input("https://mdm.example.com", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_URI).apply {
                setText(serverConfig.baseUrl())
                id = R.id.enrollment_server
            }
            addView(serverInput)
            addView(text("Mã đăng ký", 13f, TEXT, bold = true).apply { setPadding(0, dp(16), 0, 0) })
            tokenInput = input("Nhập mã đăng ký", InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_VISIBLE_PASSWORD).apply {
                id = R.id.enrollment_token
                imeOptions = android.view.inputmethod.EditorInfo.IME_ACTION_DONE
                setOnEditorActionListener { _, action, _ ->
                    if (action == android.view.inputmethod.EditorInfo.IME_ACTION_DONE) { saveManualEnrollment(); true } else false
                }
            }
            addView(tokenInput)
            enrollButton = action("Đăng ký thiết bị").apply { setOnClickListener { saveManualEnrollment() } }
            addView(enrollButton, LinearLayout.LayoutParams(MATCH, dp(52)).apply { topMargin = dp(20) })
            manualEnrollStatus = text("", 14f, ALERT).apply {
                setPadding(0, dp(12), 0, 0)
                accessibilityLiveRegion = View.ACCESSIBILITY_LIVE_REGION_POLITE
            }
            addView(manualEnrollStatus)
        }
        root.addView(manualEnrollSection)

        scheduleSection = card().apply {
            visibility = View.GONE
            addView(text("Lịch sử dụng ứng dụng", 18f, TEXT, bold = true))
            restCard = text("", 14f, MUTED).apply { setPadding(0, dp(10), 0, dp(8)) }
            addView(restCard)
            addView(action("Xem lịch sử dụng", primary = false).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, com.mdmesh.agent.usage.RestScheduleActivity::class.java)) }
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
        }
        root.addView(scheduleSection)
        root.addView(card().apply {
            addView(text("Truy cập mạng", 18f, TEXT, bold = true))
            addView(action("Xem truy cập bị chặn / gửi yêu cầu", primary = false).apply {
                setOnClickListener { startActivity(Intent(this@MainActivity, com.mdmesh.agent.web.NetworkAccessActivity::class.java)) }
            }, LinearLayout.LayoutParams(MATCH, dp(48)))
        })

        val details = column().apply { visibility = View.GONE }
        root.addView(card().apply {
            val toggle = action("Thông tin hỗ trợ  +", primary = false).apply {
                gravity = Gravity.START or Gravity.CENTER_VERTICAL
                setOnClickListener {
                    val expanded = details.visibility != View.VISIBLE
                    details.visibility = if (expanded) View.VISIBLE else View.GONE
                    text = if (expanded) "Thông tin hỗ trợ  −" else "Thông tin hỗ trợ  +"
                }
            }
            addView(toggle, LinearLayout.LayoutParams(MATCH, dp(48)))
            details.addView(text("Mã thiết bị", 12f, MUTED).apply { setPadding(0, dp(16), 0, dp(4)) })
            deviceIdValue = text("Chưa đăng ký", 15f, TEXT, mono = true).apply { setTextIsSelectable(true) }
            details.addView(deviceIdValue)
            details.addView(text("Máy chủ", 12f, MUTED).apply { setPadding(0, dp(16), 0, dp(4)) })
            serverValue = text(serverConfig.baseUrl(), 14f, TEXT).apply { setTextIsSelectable(true) }
            details.addView(serverValue)
            details.addView(text("Phiên bản Agent", 12f, MUTED).apply { setPadding(0, dp(16), 0, dp(4)) })
            details.addView(text("${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})", 14f, TEXT))
            details.addView(action("Sao chép thông tin", primary = false).apply {
                setOnClickListener {
                    getSystemService(ClipboardManager::class.java).setPrimaryClip(ClipData.newPlainText(
                        "MDMesh", "Device: ${deviceIdValue.text}\nServer: ${serverValue.text}\nAgent: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})",
                    ))
                    if (Build.VERSION.SDK_INT < 33) Toast.makeText(this@MainActivity, "Đã sao chép thông tin", Toast.LENGTH_SHORT).show()
                }
            }, LinearLayout.LayoutParams(MATCH, dp(48)).apply { topMargin = dp(16) })
            addView(details)
        })
        root.addView(text("Được quản lý bởi MDMesh", 12f, MUTED).apply {
            gravity = Gravity.CENTER
            setPadding(0, dp(12), 0, 0)
        })
        return ScrollView(this).apply {
            setBackgroundColor(BACKGROUND)
            isFillViewport = true
            addView(root)
            ViewCompat.setOnApplyWindowInsetsListener(this) { view, insets ->
                val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime())
                view.setPadding(bars.left, bars.top, bars.right, bars.bottom)
                insets
            }
        }
    }

    private fun column() = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }

    private fun card() = column().apply {
        setPadding(dp(20), dp(20), dp(20), dp(20))
        background = surface(Color.WHITE, BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, ViewGroup.LayoutParams.WRAP_CONTENT).apply { bottomMargin = dp(16) }
    }

    private fun surface(color: Int, stroke: Int = color) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(16).toFloat()
        setStroke(dp(1), stroke)
    }

    private fun divider() = View(this).apply {
        setBackgroundColor(BORDER)
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(1)).apply { topMargin = dp(20); bottomMargin = dp(16) }
    }

    private fun input(placeholder: String, type: Int) = EditText(this).apply {
        hint = placeholder
        inputType = type
        setSingleLine(true)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 15f)
        setTextColor(TEXT)
        setHintTextColor(MUTED)
        setPadding(dp(14), dp(12), dp(14), dp(12))
        background = android.graphics.drawable.StateListDrawable().apply {
            addState(intArrayOf(android.R.attr.state_focused), surface(Color.WHITE, PRIMARY))
            addState(intArrayOf(), surface(BACKGROUND, BORDER))
        }
        backgroundTintList = null
        layoutParams = LinearLayout.LayoutParams(MATCH, dp(54)).apply { topMargin = dp(8) }
    }

    private fun action(title:String,primary:Boolean=true):Button=AgentUi.button(this,title,primary)

    private fun text(s: String, sizeSp: Float, color: Int, bold: Boolean = false, mono: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sizeSp)
        setTextColor(color)
        if (bold) setTypeface(typeface, Typeface.BOLD)
        if (mono) typeface = Typeface.MONOSPACE
        setLineSpacing(dp(3).toFloat(), 1f)
    }

    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()

    private companion object {
        val TOKEN_PATTERN = Regex("^[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}$")
        const val MATCH = ViewGroup.LayoutParams.MATCH_PARENT
        val BACKGROUND = Color.parseColor("#F4F7FC")
        val BORDER = Color.parseColor("#CFD8E5")
        val TEXT = Color.parseColor("#172B4D")
        val MUTED = Color.parseColor("#52647D")
        val PRIMARY = Color.parseColor("#075CCB")
        val OK = Color.parseColor("#16734B")
        val ALERT = Color.parseColor("#B42318")
    }
}
