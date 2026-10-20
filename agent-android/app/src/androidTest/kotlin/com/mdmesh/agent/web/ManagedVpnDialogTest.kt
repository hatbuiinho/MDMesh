package com.mdmesh.agent.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import org.junit.Assert.*
import org.junit.Test
import org.junit.Assume
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class ManagedVpnDialogTest {
    @Test fun vpnDialogCanBeBlockedAndRestored() {
        Assume.assumeTrue("Explicit opt-in required on a managed device",
            InstrumentationRegistry.getArguments().getString("allowManagedVpnDialogTest") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val dpm = context.getSystemService(DevicePolicyManager::class.java)
        val admin = ComponentName(context.packageName, "com.mdmesh.agent.admin.AdminReceiver")
        val pkg = "com.android.vpndialogs"
        assertTrue(dpm.isDeviceOwnerApp(context.packageName))
        val initial = context.packageManager.isPackageSuspended(pkg)
        try {
            assertTrue(dpm.setPackagesSuspended(admin, arrayOf(pkg), true).isEmpty())
            assertTrue(context.packageManager.isPackageSuspended(pkg))
            assertTrue(dpm.setPackagesSuspended(admin, arrayOf(pkg), false).isEmpty())
            assertFalse(context.packageManager.isPackageSuspended(pkg))
        } finally {
            assertTrue(dpm.setPackagesSuspended(admin, arrayOf(pkg), initial).isEmpty())
            assertEquals(initial, context.packageManager.isPackageSuspended(pkg))
        }
    }
}
