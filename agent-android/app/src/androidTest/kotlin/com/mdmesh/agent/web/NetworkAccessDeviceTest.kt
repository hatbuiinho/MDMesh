package com.mdmesh.agent.web

import android.app.admin.DevicePolicyManager
import android.content.ComponentName
import android.content.Intent
import android.content.Context
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.ext.junit.runners.AndroidJUnit4
import com.mdmesh.agent.admin.AdminReceiver
import com.mdmesh.policy.DeviceOwnerPackageSuspensionManager
import com.mdmesh.core.config.ServerConfigStore
import com.mdmesh.proto.WebAccessPolicy
import com.mdmesh.proto.NetworkRule
import com.mdmesh.proto.NetworkGrant
import kotlinx.coroutines.runBlocking
import org.junit.*
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Opt-in on an isolated Device Owner emulator; never provisions or modifies a user's phone. */
@RunWith(AndroidJUnit4::class)
class NetworkAccessDeviceTest {
    private val instrumentation = InstrumentationRegistry.getInstrumentation()
    private val context get() = instrumentation.targetContext
    private val chat = "com.mdmesh.probe.chat"
    private val browser = "com.mdmesh.probe.browser"
    private fun shell(command: String): String = instrumentation.uiAutomation.executeShellCommand(command).use {
        java.io.FileInputStream(it.fileDescriptor).bufferedReader().readText()
    }
    private fun apply(grants: List<NetworkGrant> = emptyList(), hash: String = "device-test") {
        val dpm=context.getSystemService(DevicePolicyManager::class.java)
        Assume.assumeTrue("Requires isolated test Device Owner", dpm.isDeviceOwnerApp(context.packageName))
        val manager=AndroidWebAccessManager(context,dpm,ComponentName(context,AdminReceiver::class.java),ServerConfigStore(context),DeviceOwnerPackageSuspensionManager(context,dpm,ComponentName(context,AdminReceiver::class.java)))
        val policy=WebAccessPolicy(mode="OFF",policyHash=hash,rules=listOf(NetworkRule("video","Video","BLOCKLIST","SELECTED",listOf(chat,browser),listOf("example.com"))),grants=grants)
        val outcome=runBlocking { manager.apply(policy) }
        assertEquals("applied",outcome)
        Thread.sleep(1500)
    }
    private fun probe(pkg: String, mode: String = "direct"): String {
        shell("logcat -c")
        shell("am start -W -n $pkg/com.mdmesh.probe.ProbeActivity --es mode $mode")
        var output=""
        repeat(40) {
            Thread.sleep(250)
            output=shell("logcat -d -s MDMeshProbe:I")
            if(output.contains("RESULT $pkg"))return output
        }
        fail("No probe result: $output");return output
    }
    @Test fun knownApplicationGrantCannotUnblockAnotherApplication() {
        Assume.assumeTrue(shell("pm path $chat").contains("package:")&&shell("pm path $browser").contains("package:"))
        context.startActivity(Intent(context,com.mdmesh.agent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        apply()
        assertTrue(probe(chat).contains("rcode=3"))
        val events=NetworkAccessStore.history(context)
        assertTrue("Direct DNS must identify requesting helper app",events.any { it.optString("packageName")==chat&&it.optString("domain")=="example.com" })
        apply(listOf(NetworkGrant(42,"example.com",listOf(chat),null)))
        val allowed = probe(chat)
        if (!allowed.contains("rcode=0")) {
            println("Runtime: ${WebFilterRuntime.applied} failure=${WebFilterRuntime.failure}")
            println(shell("dumpsys vpn_management"))
        }
        assertTrue(allowed, allowed.contains("rcode=0"))
        assertTrue(probe(browser).contains("rcode=3"))
        // An expired grant must never allow DNS, including when restored from a persisted policy.
        apply(listOf(NetworkGrant(43,"example.com",listOf(chat),System.currentTimeMillis()-1)))
        assertTrue(probe(chat).contains("rcode=3"))
    }
    @Test fun systemResolverIsBlockedAndMissingOwnerIsNotInvented() {
        Assume.assumeTrue(shell("pm path $chat").contains("package:"))
        context.startActivity(Intent(context,com.mdmesh.agent.MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
        apply(hash="system-resolver-test")
        val result=probe(chat,"system")
        assertTrue(result,result.contains("blocked=true"))
        val rows=NetworkAccessStore.history(context).filter { it.optString("policyHash")=="system-resolver-test" }
        assertTrue(rows.isNotEmpty())
        assertTrue(rows.all { it.optString("packageName") in listOf("",chat) })
    }
    @After fun stopTestTunnel() {
        val dpm=context.getSystemService(DevicePolicyManager::class.java)
        if(dpm.isDeviceOwnerApp(context.packageName))runBlocking {
            AndroidWebAccessManager(context,dpm,ComponentName(context,AdminReceiver::class.java),ServerConfigStore(context),DeviceOwnerPackageSuspensionManager(context,dpm,ComponentName(context,AdminReceiver::class.java))).apply(null)
        }
    }
}
