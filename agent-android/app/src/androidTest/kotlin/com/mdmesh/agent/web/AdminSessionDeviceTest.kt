package com.mdmesh.agent.web

import android.content.Context
import android.content.Intent
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.mdmesh.core.config.ServerConfigStore
import org.junit.Test
import org.junit.Before
import org.junit.Assume
import org.junit.Assert.*
import org.junit.runner.RunWith

/** Run on an isolated emulator: verifies lifecycle, encryption and cross-client logout. */
@RunWith(AndroidJUnit4::class)
class AdminSessionDeviceTest {
    private val instrumentation=InstrumentationRegistry.getInstrumentation()
    private val context get()=instrumentation.targetContext
    @Before fun isolatedEmulatorOnly() { Assume.assumeTrue("Auth fixtures must never run on a physical device",android.os.Build.HARDWARE in setOf("ranchu","goldfish")) }
    @Test fun accountSurvivesScreenNavigationAndLogoutIsShared() {
        val store=AdminSessionStore.get(context)
        val generation=store.beginLogin()
        store.save(generation,"JSESSIONID=isolated-session-fixture","QA Admin",ServerConfigStore(context).baseUrl())
        try {
            val firstClient=NetworkAccessClient(context)
            assertTrue(firstClient.isAdmin())
            val prefs=context.getSharedPreferences("admin_session",Context.MODE_PRIVATE)
            assertFalse(prefs.all.toString().contains("isolated-session-fixture"))
            assertFalse(prefs.all.toString().contains("QA Admin"))
            val constructor=AdminSessionStore::class.java.getDeclaredConstructor(Context::class.java).apply {isAccessible=true}
            val restored=constructor.newInstance(context)
            assertEquals("QA Admin",restored.snapshot().name)
            assertEquals("JSESSIONID=isolated-session-fixture",restored.snapshot().cookie)
            val network=instrumentation.startActivitySync(Intent(context,NetworkAccessActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {network.finish()}
            instrumentation.waitForIdleSync()
            assertTrue("Leaving Network Access must not clear global auth",firstClient.isAdmin())
            val account=instrumentation.startActivitySync(Intent(context,AdminAccountActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK))
            instrumentation.runOnMainSync {account.finish()}
            instrumentation.waitForIdleSync()
            assertTrue("Leaving the account screen must not clear global auth",NetworkAccessClient(context).isAdmin())
            assertEquals("QA Admin",NetworkAccessClient(context).accountName)
            NetworkAccessClient(context).clearSession()
            assertFalse(NetworkAccessClient(context).isAdmin())
            assertFalse("Existing clients observe shared logout",firstClient.isAdmin())
            assertTrue(prefs.all.isEmpty())
        }finally {store.clear()}
    }
    @Test fun lateLoginCannotResurrectLoggedOutSession() {
        val store=AdminSessionStore.get(context)
        val generation=store.beginLogin();store.clear()
        try {store.save(generation,"JSESSIONID=late","QA",ServerConfigStore(context).baseUrl());fail("Stale login response must be rejected")}
        catch(expected:IllegalStateException){assertFalse(NetworkAccessClient(context).isAdmin())}
    }
}
