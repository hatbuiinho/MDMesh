package com.mdmesh.agent.web

import kotlinx.coroutines.*
import kotlinx.coroutines.sync.Mutex
import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class AdminSessionRecoveryTest {
    private var state=AdminSessionStore.Snapshot(cookie="old-cookie",name="Admin",server="https://example.test",refreshToken="old-refresh",refreshExpiresAt=900000,accessExpiresAt=1000)
    private var renewals=0
    private var clears=0
    private fun recovery(valid:Boolean=false,renew:suspend (AdminSessionStore.Snapshot)->AdminSessionStore.Snapshot={
        renewals++;delay(10);state=it.copy(cookie="new-cookie",refreshToken="new-refresh",accessExpiresAt=900000);state
    })=AdminSessionRecovery({state},{cookie->if(state.cookie==cookie){clears++;state=AdminSessionStore.Snapshot()}},renew,{valid},Mutex(),now={1000})
    @Test fun `simultaneous screen requests only rotate once`()=runBlocking {
        val recovery=recovery()
        val cookies=coroutineScope{(1..8).map{async{recovery.run{it.cookie}}}.awaitAll()}
        assertEquals(1,renewals);assertTrue(cookies.all{it=="new-cookie"});assertEquals(0,clears)
    }
    @Test fun `permission denial with valid cookie does not renew or logout`()=runBlocking {
        state=state.copy(accessExpiresAt=900000)
        try{recovery(valid=true).run<Unit>{throw NetworkHttpError(403)};fail("Expected permission denial")}
        catch(expected:NetworkAccessRejected){}
        assertEquals(0,renewals);assertEquals(0,clears);assertEquals("old-cookie",state.cookie)
    }
    @Test fun `expired servlet cookie renews and retries once`()=runBlocking {
        state=state.copy(accessExpiresAt=900000);var attempts=0
        val result=recovery().run{attempts++;if(it.cookie=="old-cookie")throw NetworkHttpError(403);"approved"}
        assertEquals("approved",result);assertEquals(2,attempts);assertEquals(1,renewals)
    }
    @Test fun `offline during renewal preserves encrypted session for retry`()=runBlocking {
        try{recovery(renew={throw IOException("offline")}).run{it.cookie};fail("Expected offline")}
        catch(expected:IOException){}
        assertEquals(0,clears);assertEquals("old-refresh",state.refreshToken)
    }
    @Test fun `server confirmed refresh revocation clears shared session`()=runBlocking {
        try{recovery(renew={throw NetworkHttpError(401)}).run{it.cookie};fail("Expected invalid refresh")}
        catch(expected:NetworkHttpError){}
        assertEquals(1,clears);assertTrue(state.cookie.isEmpty())
    }
}
