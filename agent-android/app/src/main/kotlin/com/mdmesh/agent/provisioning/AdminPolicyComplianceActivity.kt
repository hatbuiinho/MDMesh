package com.mdmesh.agent.provisioning

import android.app.Activity
import android.app.admin.DevicePolicyManager
import android.content.Intent
import android.graphics.Typeface
import android.os.Bundle
import android.os.PersistableBundle
import android.view.Gravity
import android.view.ViewGroup
import android.widget.Button
import android.widget.LinearLayout
import android.widget.TextView
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.core.action.ResetPasswordTokenStore
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.core.store.EnrollTokenStore
import com.mdmesh.core.sync.CheckInWorker
import com.mdmesh.policy.PolicyManager
import com.mdmesh.policy.wifi.DpmHandle
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Handles `ACTION_ADMIN_POLICY_COMPLIANCE`, launched right after provisioning.
 *
 * On Android 12+ (the modern provisioning contract) the `PROVISIONING_ADMIN_EXTRAS_BUNDLE`
 * — which carries our single-use enroll token and the deployment's server URL — is delivered
 * to THIS activity's intent, not to [AdminReceiver.onProfileProvisioningComplete]. So this is
 * where we capture both, persist them, and kick an immediate check-in so the device enrolls
 * within seconds — against the server the QR named, not the baked fallback (which in release
 * builds is a placeholder).
 */
class AdminPolicyComplianceActivity : Activity() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var statusView: TextView
    private var completing = false
    private var openedSettings = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val ctx = applicationContext
        // Server URL first (synchronous SharedPreferences), so no check-in scheduled below can
        // ever run against the baked fallback. save() is a no-op for an absent/blank extra.
        ServerConfigStore(ctx).save(extrasString(AdminReceiver.EXTRA_SERVER_URL))
        applyBaselinePolicy(ctx)

        openedSettings = savedInstanceState?.getBoolean(STATE_OPENED_SETTINGS) == true
        setContentView(buildMandatoryUsageAccessView())

        // Open the required system screen on first entry. If the user comes back without granting
        // access, this activity remains visible and cannot report provisioning as complete.
        if (!UsageAccessGate.isGranted(this) && !openedSettings) {
            openedSettings = true
            statusView.post { openUsageAccessSettings() }
        }
    }

    override fun onResume() {
        super.onResume()
        if (::statusView.isInitialized) {
            if (UsageAccessGate.isGranted(this)) {
                completeProvisioning()
            } else {
                statusView.text = "Required: enable usage access for MDMesh Agent to continue."
            }
        }
    }

    override fun onSaveInstanceState(outState: Bundle) {
        outState.putBoolean(STATE_OPENED_SETTINGS, openedSettings)
        super.onSaveInstanceState(outState)
    }

    override fun onDestroy() {
        scope.cancel()
        super.onDestroy()
    }

    private fun completeProvisioning() {
        if (completing) return
        completing = true
        statusView.text = "Usage access enabled. Completing enrollment…"

        val ctx = applicationContext
        val token = extrasString(AdminReceiver.EXTRA_ENROLL_TOKEN)
        scope.launch {
            withContext(Dispatchers.IO) {
                if (!token.isNullOrBlank()) {
                    EnrollTokenStore(ctx).save(token)
                }
            }
            CheckInWorker.schedule(ctx)
            CheckInWorker.scheduleNow(ctx)
            setResult(RESULT_OK)
            finish()
        }
    }

    private fun buildMandatoryUsageAccessView(): LinearLayout {
        val density = resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_VERTICAL
            setPadding(dp(32), dp(32), dp(32), dp(32))

            addView(TextView(context).apply {
                text = "Usage access required"
                textSize = 24f
                setTypeface(typeface, Typeface.BOLD)
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ))

            addView(TextView(context).apply {
                text = "MDMesh uses app usage data to enforce usage limits and report device compliance. Enable access before enrollment can finish."
                textSize = 16f
                setPadding(0, dp(16), 0, dp(20))
            })

            statusView = TextView(context).apply {
                textSize = 15f
            }
            addView(statusView)

            addView(Button(context).apply {
                text = "Open usage access settings"
                setOnClickListener { openUsageAccessSettings() }
            }, LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT,
            ).apply { topMargin = dp(20) })
        }
    }

    private fun openUsageAccessSettings() {
        val intent = UsageAccessGate.settingsIntent()
        if (intent.resolveActivity(packageManager) != null) {
            startActivity(intent)
        } else {
            statusView.text = "Usage Access settings are unavailable on this device. Open Settings and enable usage access for MDMesh Agent."
            runCatching { startActivity(Intent(android.provider.Settings.ACTION_SETTINGS)) }
        }
    }

    /** Benign Device-Owner baseline: auto-grant runtime permissions to managed apps so they
     *  never prompt. Restrictive policies are pushed by the admin via commands, not here. */
    private fun applyBaselinePolicy(ctx: android.content.Context) {
        runCatching {
            val dpm = ctx.getSystemService(android.content.Context.DEVICE_POLICY_SERVICE) as DevicePolicyManager
            val handle = DpmHandle(dpm, AdminReceiver.componentName(ctx))
            PolicyManager(handle).setPermissionAutoGrant()
            // Provision the DO reset-password token once, so device.passcodeReset works later.
            ResetPasswordTokenStore(ctx, handle).ensureToken()
            // Silently grant the telemetry runtime permissions as Device Owner.
            listOf(
                android.Manifest.permission.READ_PHONE_STATE,
                android.Manifest.permission.READ_PHONE_NUMBERS,
                android.Manifest.permission.ACCESS_FINE_LOCATION,
                android.Manifest.permission.ACCESS_COARSE_LOCATION,
            ).forEach { perm ->
                runCatching {
                    dpm.setPermissionGrantState(
                        handle.admin, ctx.packageName, perm,
                        DevicePolicyManager.PERMISSION_GRANT_STATE_GRANTED,
                    )
                }
            }
            // Enable location services (DO) so Wi-Fi SSID telemetry is readable on Android 10+.
            runCatching {
                if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
                    dpm.setLocationEnabled(handle.admin, true)
                }
            }
        }
    }

    @Suppress("DEPRECATION") // typed getParcelableExtra is API 33+; we support minSdk 24
    private fun extrasString(key: String): String? =
        intent.getParcelableExtra<PersistableBundle>(
            DevicePolicyManager.EXTRA_PROVISIONING_ADMIN_EXTRAS_BUNDLE,
        )?.getString(key)

    companion object {
        private const val STATE_OPENED_SETTINGS = "opened_usage_access_settings"
    }
}
