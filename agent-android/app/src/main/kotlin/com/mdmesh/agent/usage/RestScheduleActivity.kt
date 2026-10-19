package com.mdmesh.agent.usage

import android.app.AlarmManager
import android.os.*
import android.content.*
import android.graphics.Color
import android.widget.*
import androidx.activity.ComponentActivity
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import androidx.lifecycle.Lifecycle
import com.mdmesh.core.usage.AppUsageManager
import com.mdmesh.core.usage.RestScheduleEngine
import dagger.hilt.android.AndroidEntryPoint
import kotlinx.coroutines.*
import java.time.Instant
import java.time.format.DateTimeFormatter
import javax.inject.Inject

@AndroidEntryPoint
class RestScheduleActivity : ComponentActivity() {
    @Inject lateinit var usage: AppUsageManager
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.RESUMED) {
                while (true) {
                    withContext(Dispatchers.IO) { runCatching { usage.evaluateAndReport() } }
                    render()
                    delay(30_000)
                }
            }
        }
    }
    private fun render() {
        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL; setPadding(32, 32, 32, 32)
            setBackgroundColor(Color.rgb(14, 17, 23))
        }
        fun line(value: String, size: Float = 16f) {
            column.addView(TextView(this).apply { text = value; textSize = size; setTextColor(Color.WHITE); setPadding(0, 14, 0, 14) })
        }
        column.addView(Button(this).apply { text = "Đóng"; setOnClickListener { finish() } })
        line("Lịch nghỉ và sử dụng ứng dụng", 24f)
        line(summary(this, usage))
        if (Build.VERSION.SDK_INT >= 31 && !getSystemService(AlarmManager::class.java).canScheduleExactAlarms()) {
            column.addView(Button(this).apply {
                text = "Cho phép hẹn giờ chính xác"
                setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_REQUEST_SCHEDULE_EXACT_ALARM, android.net.Uri.parse("package:$packageName"))) } }
            })
        }
        if (!getSystemService(android.app.NotificationManager::class.java).areNotificationsEnabled()) {
            column.addView(Button(this).apply {
                text = "Bật thông báo lịch nghỉ"
                setOnClickListener { runCatching { startActivity(Intent(android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS, android.net.Uri.parse("package:$packageName"))) } }
            })
        }
        val policy = usage.currentPolicy()
        if (policy == null || policy.schedules.none { it.enabled }) line("Chưa có lịch nghỉ đang bật.")
        else {
            val now = Instant.now(); val zone = RestScheduleEngine.zone(policy)
            line("Múi giờ: ${policy.timezone}")
            val occurrences = RestScheduleEngine.occurrences(policy, now).filter { it.end > now }
            val format = DateTimeFormatter.ofPattern("HH:mm dd/MM")
            occurrences.take(20).forEach { o ->
                val s = o.schedule
                val scope = if (s.scope == "ALL") "Tất cả ứng dụng có thể quản lý" else s.packages.joinToString(", ") { appLabel(this, it) }
                val exceptions = s.exceptions.joinToString(", ") { appLabel(this, it) }.ifEmpty { "Không có ngoại lệ riêng" }
                line("${if (now >= o.start) "ĐANG NGHỈ · " else ""}${s.name}\n${o.start.atZone(zone).format(format)} → ${o.end.atZone(zone).format(format)}\n$scope\nNgoại lệ: $exceptions")
            }
            line("Agent và thành phần hệ thống thiết yếu luôn được bảo vệ. Hạn mức ngày và các lịch khác vẫn áp dụng.", 14f)
        }
        setContentView(ScrollView(this).apply { addView(column) })
    }
    companion object {
        fun appLabel(context: Context, pkg: String): String = runCatching {
            context.packageManager.getApplicationLabel(context.packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        fun summary(context: Context, usage: AppUsageManager): String {
            val p = usage.currentPolicy() ?: return "Chưa có lịch nghỉ. Chạm để xem lịch."
            val now = Instant.now(); val active = RestScheduleEngine.active(p, now)
            val text = if (active.isNotEmpty()) "Đang nghỉ: " + active.joinToString(", ") { it.schedule.name }
            else RestScheduleEngine.occurrences(p, now).firstOrNull { it.start > now }?.let {
                "Lịch tiếp theo: ${it.schedule.name} · ${it.start.atZone(RestScheduleEngine.zone(p)).format(DateTimeFormatter.ofPattern("HH:mm dd/MM"))}"
            } ?: "Không có lịch nghỉ đang bật."
            val alarms = context.getSystemService(AlarmManager::class.java)
            val timing = if (Build.VERSION.SDK_INT >= 31 && !alarms.canScheduleExactAlarms()) "\nHẹn giờ chính xác chưa được cấp; lịch có thể áp dụng trễ khi máy ngủ." else ""
            val nm = context.getSystemService(android.app.NotificationManager::class.java)
            val notifications = if (Build.VERSION.SDK_INT >= 24 && !nm.areNotificationsEnabled()) "\nThông báo đang tắt. Bạn vẫn có thể xem lịch tại đây." else ""
            return "$text$timing$notifications\nChạm để xem lịch và các ứng dụng bị ảnh hưởng."
        }
        fun card(context: Context, usage: AppUsageManager): TextView = TextView(context).apply {
            text = summary(context, usage); textSize = 16f; setTextColor(Color.rgb(244, 185, 66)); setPadding(12, 24, 12, 24)
            setOnClickListener { context.startActivity(Intent(context, RestScheduleActivity::class.java)) }
        }
    }
}
