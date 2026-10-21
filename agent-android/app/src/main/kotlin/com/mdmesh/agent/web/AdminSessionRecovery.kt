package com.mdmesh.agent.web

import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Serialize private calls across screens so a rotated refresh token is never used twice. */
internal class AdminSessionRecovery(
    private val snapshot: () -> AdminSessionStore.Snapshot,
    private val clear: (String) -> Unit,
    private val renew: suspend (AdminSessionStore.Snapshot) -> AdminSessionStore.Snapshot,
    private val validCookie: suspend (AdminSessionStore.Snapshot) -> Boolean,
    private val mutex: Mutex,
    private val prepare: suspend (AdminSessionStore.Snapshot) -> AdminSessionStore.Snapshot = { it },
    private val now: () -> Long = System::currentTimeMillis,
) {
    private suspend fun refreshed(state: AdminSessionStore.Snapshot): AdminSessionStore.Snapshot = try {
        renew(state)
    } catch(e: NetworkHttpError) {
        if(e.code in setOf(401,403)) clear(state.cookie)
        throw e
    }
    suspend fun <T> run(execute: suspend (AdminSessionStore.Snapshot) -> T): T = mutex.withLock {
        var state=snapshot()
        check(state.cookie.isNotEmpty()){ "Phiên admin đã khóa. Đăng nhập lại." }
        state=prepare(state)
        if(state.refreshToken.isNotEmpty() && state.accessExpiresAt>0 && state.accessExpiresAt<=now()+30000)state=refreshed(state)
        try {
            execute(state)
        } catch(e:NetworkHttpError) {
            if(e.code !in setOf(401,403))throw e
            if(e.code==403 && validCookie(state))throw NetworkAccessRejected("Tài khoản không có quyền thực hiện thao tác này.")
            if(state.refreshToken.isEmpty()){clear(state.cookie);error("Phiên admin đã hết hạn. Đăng nhập lại từ Tài khoản quản trị.")}
            val renewed=refreshed(state)
            try{execute(renewed)}
            catch(retry:NetworkHttpError){
                if(retry.code==401 || retry.code==403 && !validCookie(renewed))clear(renewed.cookie)
                throw retry
            }
        }
    }
}
