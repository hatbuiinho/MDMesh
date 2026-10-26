package com.mdmesh.agent.install

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import com.mdmesh.agent.R
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.agent.ui.AgentUi

/** User-initiated foreground route from the removal notification to Android's uninstaller. */
class AgentRemovalActivity : AppCompatActivity() {
    private var message = ""
    private val uninstall = registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        if (result.resultCode == Activity.RESULT_OK) finish() else {
            message = "Ứng dụng chưa được gỡ. Bạn có thể thử lại hoặc mở Cài đặt ứng dụng."
            render()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        render()
    }

    private fun managementReleased(): Boolean {
        val dpm = getSystemService(DevicePolicyManager::class.java)
        return !dpm.isDeviceOwnerApp(packageName) && !dpm.isProfileOwnerApp(packageName) &&
            !dpm.isAdminActive(AdminReceiver.componentName(this))
    }

    private fun render() {
        val released = managementReleased()
        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(getColor(R.color.network_background))
        }
        root.addView(AgentUi.toolbar(this, "Gỡ MDMesh Agent", account = false))
        val body = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        fun line(value: String) {
            body.addView(TextView(this).apply {
                text = value
                textSize = 16f
                setTextColor(getColor(R.color.network_on_surface))
                setPadding(0, AgentUi.dp(this@AgentRemovalActivity, 16), 0, AgentUi.dp(this@AgentRemovalActivity, 16))
            })
        }
        line(if (released) "Quyền quản lý đã được gỡ. Bấm Gỡ ứng dụng, rồi xác nhận trong hộp thoại Android. Dữ liệu của các ứng dụng khác không bị xóa."
            else "Agent vẫn có quyền quản trị. Chưa thể gỡ ứng dụng. Kiểm tra kết quả lệnh Remove MDM Agent trên web quản lý.")
        if (message.isNotBlank()) line(message)
        body.addView(AgentUi.button(this, "Gỡ ứng dụng").apply {
            isEnabled = released
            setOnClickListener {
                if (!managementReleased()) { render(); return@setOnClickListener }
                try {
                    @Suppress("DEPRECATION")
                    val intent = Intent(Intent.ACTION_UNINSTALL_PACKAGE, Uri.parse("package:$packageName"))
                        .putExtra(Intent.EXTRA_RETURN_RESULT, true)
                    uninstall.launch(intent)
                } catch (e: Exception) {
                    message = "Không mở được hộp thoại gỡ: ${e.message ?: "Android từ chối yêu cầu"}. Hãy mở Cài đặt ứng dụng bên dưới."
                    render()
                }
            }
        })
        body.addView(AgentUi.button(this, "Mở Cài đặt ứng dụng", primary = false).apply {
            setOnClickListener {
                try {
                    startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, Uri.parse("package:$packageName")))
                } catch (e: Exception) {
                    message = "Không mở được Cài đặt ứng dụng: ${e.message ?: "Android từ chối yêu cầu"}"
                    render()
                }
            }
        })
        root.addView(ScrollView(this).apply { addView(body) }, LinearLayout.LayoutParams(-1, 0, 1f))
        AgentUi.insets(this, root)
        setContentView(root)
    }
}
