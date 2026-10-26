package com.mdmesh.core.command.handlers

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.os.Build
import androidx.core.app.NotificationCompat
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.UserManager
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.kiosk.KioskApplier
import com.mdmesh.core.store.ConfigStateStore
import com.mdmesh.kiosk.KioskResult
import com.mdmesh.policy.wifi.DpmHandle
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.DeviceAction

/** Releases management without erasing user data, then requests Android's uninstall UI. */
class DeviceUninstallAgentHandler(
    private val context: Context,
    private val handle: DpmHandle,
    private val kiosk: KioskApplier,
    private val config: ConfigStateStore,
) : CommandHandler {
    override val type = DeviceAction.UNINSTALL_AGENT

    @Suppress("DEPRECATION")
    override suspend fun handle(command: CommandEnvelope): CommandResult = runCatching {
        val pkg = context.packageName
        // Profile-owner removal can delete a work profile; this command is for fully managed devices.
        if (handle.dpm.isProfileOwnerApp(pkg)) {
            return CommandResults.failed(command, "Profile-owner removal is not supported; no changes made")
        }
        val uninstall = Intent(Intent.ACTION_DELETE, Uri.parse("package:$pkg"))
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        val confirmation = Intent().setClassName(context.packageName, "com.mdmesh.agent.install.AgentRemovalActivity")
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        if (uninstall.resolveActivity(context.packageManager) == null) {
            return CommandResults.failed(command, "Android uninstall screen unavailable; no changes made")
        }
        if (handle.dpm.isDeviceOwnerApp(pkg)) {
            val exited = kiosk.exit()
            if (exited is KioskResult.Failed) return CommandResults.failed(command, exited.reason)
            handle.dpm.clearUserRestriction(handle.admin, UserManager.DISALLOW_UNINSTALL_APPS)
            handle.dpm.setUninstallBlocked(handle.admin, pkg, false)
            // protectAgentProcess() marks us as an owner-protected package on API 30+.
            // Clear that list while we still have authority: some devices retain
            // PackageManager's protection after the owner is removed and reject uninstall.
            // Let failures abort removal rather than relinquishing the authority to retry.
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
                handle.dpm.setUserControlDisabledPackages(handle.admin, emptyList())
                check(handle.dpm.getUserControlDisabledPackages(handle.admin).isEmpty()) {
                    "Owner-protected packages were not cleared; Device Owner retained"
                }
            }
            handle.dpm.clearDeviceOwnerApp(pkg)
        }
        if (handle.dpm.isAdminActive(handle.admin)) handle.dpm.removeActiveAdmin(handle.admin)
        config.clear()
        // After relinquishing Device Owner Android can block a background activity launch.
        // Keep a user-initiated route to the same uninstall screen available in that case.
        runCatching {
            val manager = context.getSystemService(NotificationManager::class.java)
            val channel = "mdmesh_agent_removal"
            if (Build.VERSION.SDK_INT >= 26) {
                manager.createNotificationChannel(NotificationChannel(channel, "Agent removal", NotificationManager.IMPORTANCE_HIGH))
            }
            val pending = PendingIntent.getActivity(context, 7001, confirmation, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            manager.notify(7001, NotificationCompat.Builder(context, channel)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle("Gỡ MDMesh Agent")
                .setContentText("Đã bỏ quyền quản lý. Chạm để mở xác nhận gỡ ứng dụng.")
                .setStyle(NotificationCompat.BigTextStyle().bigText("Đã bỏ quyền quản lý thiết bị. Chạm thông báo hoặc chọn Mở xác nhận gỡ, rồi bấm Gỡ ứng dụng trên màn hình tiếp theo."))
                .addAction(android.R.drawable.ic_menu_delete, "Mở xác nhận gỡ", pending)
                .setContentIntent(pending).setAutoCancel(true).build())
        }
        runCatching { context.startActivity(confirmation) }
        CommandResults.done(command, "Management released; uninstall requested. Confirm removal on the device (notification or Android app settings). APK removal is not verified.")
    }.getOrElse {
        CommandResults.failed(command, "Removal incomplete (management may already be released): ${it.message}. Open Android app settings to finish uninstalling.")
    }
}
