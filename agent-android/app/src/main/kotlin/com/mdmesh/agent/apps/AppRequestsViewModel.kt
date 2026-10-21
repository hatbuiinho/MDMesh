package com.mdmesh.agent.apps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Job
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.receiveAsFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

internal sealed interface AppRequestEffect { data class OpenPlay(val packageName: String? = null) : AppRequestEffect }

/** Screen state owns drafts and async work; composables only render state and emit user events. */
internal class AppRequestsViewModel(
    private val repository: AppRequestsRepository,
    private val saved: SavedStateHandle,
) : ViewModel() {
    private val restoredApp = saved.get<String>("draftPackage")?.let {
        CatalogApp(it, saved["draftName"] ?: it, saved["draftDeveloper"] ?: "")
    }
    private val mutable = MutableStateFlow(AppRequestsState(
        admin = saved["admin"] ?: false, page = saved["page"] ?: 1,
        tab = RequestTab.entries.getOrElse(saved["tab"] ?: 0) { RequestTab.PENDING },
        query = saved["query"] ?: "", reason = saved["reason"] ?: "",
        sheet = restoredApp?.let { AppRequestSheet.Form(it) },
    ))
    val state = mutable.asStateFlow()
    private val events = Channel<AppRequestEffect>(Channel.BUFFERED)
    val effects = events.receiveAsFlow()
    private var started = false
    private var identity = ""
    private var epoch = 0
    private var loadJob: Job? = null
    private var searchJob: Job? = null
    private var historyJob: Job? = null
    private var pendingShare: String? = null
    private var searchEpoch = 0
    private var resolvingShare = false

    private fun cancelSearch() {
        searchEpoch++; searchJob?.cancel(); searchJob = null
        if (resolvingShare) {
            resolvingShare = false
            mutable.update { it.copy(loading = false) }
        }
    }

    fun start(adminInbox: Boolean, sharedPackage: String? = null) {
        if (started) { if (sharedPackage != null) acceptShare(sharedPackage); return }
        started = true
        if (!saved.contains("admin")) mutable.update { it.copy(admin = adminInbox) }
        syncAccount()
        if (sharedPackage != null) acceptShare(sharedPackage) else refresh()
    }
    private fun syncAccount(): Boolean {
        val account = repository.account()
        val changed = identity != account.identity
        if (changed) {
            identity = account.identity; epoch++
            mutable.update { it.copy(signedIn = account.signedIn,
                requests = if (it.admin) emptyList() else it.requests, loading = false,
                sheet = if (it.admin) null else it.sheet, note = "", history = emptyList(), historyLoading = false) }
            historyJob?.cancel()
        } else mutable.update { it.copy(signedIn = account.signedIn) }
        return changed
    }
    fun foreground() {
        if (!started) return
        val changed = syncAccount()
        if (changed || !state.value.loading && (state.value.sheet == null || state.value.sheet is AppRequestSheet.Details)) refresh()
    }
    fun poll() { if (state.value.sheet == null && !state.value.loading && !state.value.working) refresh() }
    fun role(admin: Boolean) {
        if (state.value.working || state.value.admin == admin) return
        loadJob?.cancel(); cancelSearch(); historyJob?.cancel(); epoch++
        saved["admin"] = admin; saved["page"] = 1; saved["tab"] = 0
        mutable.update { it.copy(admin = admin, page = 1, tab = RequestTab.PENDING, requests = emptyList(), sheet = null, message = null, loading = false, history = emptyList()) }
        syncAccount(); refresh()
    }
    fun tab(tab: RequestTab) { saved["tab"] = tab.ordinal; mutable.update { it.copy(tab = tab) } }
    fun page(delta: Int) {
        if (state.value.loading || state.value.working) return
        val page = (state.value.page + delta).coerceAtLeast(1)
        saved["page"] = page; mutable.update { it.copy(page = page, requests = emptyList()) }; refresh()
    }
    fun refresh() {
        if (state.value.working) return
        loadJob?.cancel()
        syncAccount()
        val current = state.value
        if (current.admin && !current.signedIn) { mutable.update { it.copy(loading = false, requests = emptyList()) }; return }
        val ticket = ++epoch
        mutable.update { it.copy(loading = true, message = null) }
        loadJob = viewModelScope.launch {
            try {
                val rows = repository.requests(current.admin, current.page)
                syncAccount()
                if (epoch == ticket) mutable.update {
                    val details = it.sheet as? AppRequestSheet.Details
                    it.copy(requests = rows, sheet = if (details == null) it.sheet else rows.firstOrNull { row -> row.id == details.request.id }?.let { row -> AppRequestSheet.Details(row) })
                }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                syncAccount()
                if (epoch == ticket) mutable.update { it.copy(message = e.message ?: "Không tải được yêu cầu") }
            } finally { if (epoch == ticket) mutable.update { it.copy(loading = false) } }
        }
    }
    fun picker() {
        if (state.value.working) return
        cancelSearch()
        mutable.update { it.copy(sheet = AppRequestSheet.Picker, searching = false, message = null) }
    }
    fun query(value: String) {
        cancelSearch(); saved["query"] = value.take(200)
        mutable.update { it.copy(query = value.take(200), searching = false, searchResults = emptyList(), searchMessage = null) }
    }
    fun search() {
        val query = state.value.query.trim()
        if (query.length !in 2..200) { mutable.update { it.copy(searchMessage = "Nhập từ 2 đến 200 ký tự.") }; return }
        searchJob?.cancel()
        val ticket = ++searchEpoch
        mutable.update { it.copy(searching = true, searchResults = emptyList(), searchMessage = null) }
        searchJob = viewModelScope.launch {
            try {
                val apps = repository.search(query)
                if (searchEpoch == ticket && state.value.sheet == AppRequestSheet.Picker && state.value.query.trim() == query)
                    mutable.update { it.copy(searchResults = apps, searchMessage = if (apps.isEmpty()) "Không có kết quả. Bạn có thể tìm trong Google Play rồi chia sẻ link sang Agent." else null) }
            } catch (e: Exception) {
                if (e is CancellationException) throw e
                if (searchEpoch == ticket) mutable.update { it.copy(searchMessage = e.message ?: "Không tìm được ứng dụng") }
            } finally { if (searchEpoch == ticket) mutable.update { it.copy(searching = false) } }
        }
    }
    fun choose(app: CatalogApp) {
        cancelSearch(); saved["draftPackage"] = app.packageName; saved["draftName"] = app.name; saved["draftDeveloper"] = app.developer
        saved["reason"] = ""
        mutable.update { it.copy(sheet = AppRequestSheet.Form(app), reason = "", message = null, searching = false) }
    }
    fun reason(value: String) { saved["reason"] = value.take(1000); mutable.update { it.copy(reason = value.take(1000)) } }
    fun note(value: String) { mutable.update { it.copy(note = value.take(1000)) } }
    fun dismiss() {
        if (state.value.working) return
        cancelSearch(); historyJob?.cancel()
        saved.remove<String>("draftPackage"); saved.remove<String>("draftName"); saved.remove<String>("draftDeveloper"); saved["reason"] = ""
        mutable.update { it.copy(sheet = null, searching = false, reason = "", note = "", history = emptyList(), historyLoading = false, historyError = null, message = null) }
    }
    fun acceptShare(pkg: String) {
        if (!PlayAppLink.validPackage(pkg)) return
        if (state.value.working) { pendingShare = pkg; return }
        loadJob?.cancel(); cancelSearch(); historyJob?.cancel(); epoch++
        saved["admin"] = false; saved["page"] = 1
        val ticket = ++searchEpoch
        resolvingShare = true
        mutable.update { it.copy(admin = false, page = 1, requests = emptyList(), sheet = null, loading = true, message = "Đang lấy thông tin ứng dụng…") }
        searchJob = viewModelScope.launch {
            val app = try { repository.search(pkg).firstOrNull { it.packageName == pkg } }
            catch (e: Exception) { if (e is CancellationException) throw e; null }
            if (searchEpoch != ticket) return@launch
            resolvingShare = false
            mutable.update { it.copy(loading = false) }
            searchJob = null
            choose(app ?: CatalogApp(pkg, pkg, "Chưa lấy được tên từ Google Play"))
        }
    }
    private fun afterWrite() {
        mutable.update { it.copy(working = false) }
        pendingShare?.let { pendingShare = null; acceptShare(it) }
    }
    fun submit() {
        val current = state.value
        val app = (current.sheet as? AppRequestSheet.Form)?.app ?: return
        if (current.working) return
        if (current.reason.isBlank()) { mutable.update { it.copy(message = "Nhập lý do cần cài ứng dụng.") }; return }
        mutable.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            try {
                val rows = repository.submit(app, current.reason.trim())
                mutable.update { it.copy(working = false) }; dismiss()
                mutable.update { it.copy(requests = rows, message = "Đã gửi yêu cầu") }
            } catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(message = e.message ?: "Không gửi được yêu cầu") } }
            finally { afterWrite() }
        }
    }
    fun details(request: AppInstallRequest) {
        historyJob?.cancel()
        mutable.update { it.copy(sheet = AppRequestSheet.Details(request), note = "", message = null, history = emptyList(), historyError = null, historyLoading = it.admin && it.signedIn) }
        if (state.value.admin && state.value.signedIn) {
            val account = identity
            historyJob = viewModelScope.launch {
                try {
                    val history = repository.history(request.id)
                    syncAccount()
                    if (identity == account && (state.value.sheet as? AppRequestSheet.Details)?.request?.id == request.id) mutable.update { it.copy(history = history) }
                } catch (e: Exception) { if (e is CancellationException) throw e; syncAccount(); if (identity == account) mutable.update { it.copy(historyError = e.message ?: "Không tải được lịch sử") } }
                finally { if ((state.value.sheet as? AppRequestSheet.Details)?.request?.id == request.id) mutable.update { it.copy(historyLoading = false) } }
            }
        }
    }
    fun decide(action: RequestDecision) {
        val current = state.value
        val request = (current.sheet as? AppRequestSheet.Details)?.request ?: return
        if (!current.admin || !current.signedIn || current.working) return
        mutable.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            try {
                repository.decide(request.id, action, current.note.trim())
                mutable.update { it.copy(working = false) }; dismiss(); refresh()
            } catch (e: Exception) { if (e is CancellationException) throw e; syncAccount(); mutable.update { it.copy(message = e.message ?: "Không lưu được quyết định") } }
            finally { afterWrite() }
        }
    }
    fun openPlay() { viewModelScope.launch { events.send(AppRequestEffect.OpenPlay()) } }
    fun openApproved(request: AppInstallRequest) = openApprovedId(request.id)
    fun openApprovedId(id: Int) {
        if (state.value.working) return
        mutable.update { it.copy(working = true, message = null) }
        viewModelScope.launch {
            try { events.send(AppRequestEffect.OpenPlay(repository.approvedPackage(id))) }
            catch (e: Exception) { if (e is CancellationException) throw e; mutable.update { it.copy(message = e.message ?: "Không mở được Google Play") } }
            finally { afterWrite() }
        }
    }
    fun message(message: String) { mutable.update { it.copy(message = message) } }
}
