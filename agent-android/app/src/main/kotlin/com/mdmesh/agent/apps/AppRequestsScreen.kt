package com.mdmesh.agent.apps

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import com.mdmesh.agent.ui.compose.*
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter

internal data class AppRequestsActions(
    val back: () -> Unit = {}, val account: () -> Unit = {}, val refresh: () -> Unit = {},
    val role: (Boolean) -> Unit = {}, val tab: (RequestTab) -> Unit = {}, val page: (Int) -> Unit = {},
    val picker: () -> Unit = {}, val dismiss: () -> Unit = {}, val query: (String) -> Unit = {}, val search: () -> Unit = {},
    val choose: (CatalogApp) -> Unit = {}, val reason: (String) -> Unit = {}, val submit: () -> Unit = {},
    val details: (AppInstallRequest) -> Unit = {}, val note: (String) -> Unit = {}, val decide: (RequestDecision) -> Unit = {},
    val openPlay: () -> Unit = {}, val openApproved: (AppInstallRequest) -> Unit = {},
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
internal fun AppRequestsScreen(state: AppRequestsState, actions: AppRequestsActions) {
    val visible = remember(state.requests, state.admin, state.signedIn, state.tab) { visibleAppRequests(state) }
    val listState = key(state.admin, state.page, state.tab) { rememberLazyListState() }
    Scaffold(
        containerColor = MaterialTheme.colorScheme.background,
        topBar = { AgentTopBar("Cài ứng dụng", state.loading || state.working, actions.back, actions.refresh, actions.account) },
    ) { insets ->
        Column(Modifier.fillMaxSize().padding(insets).consumeWindowInsets(insets)) {
            AgentRoleSwitch(state.admin, !state.working, actions.role, Modifier.padding(horizontal = 16.dp, vertical = 8.dp))
            if (!state.admin) Button(onClick = actions.picker, enabled = !state.working,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 4.dp).heightIn(min = 48.dp)) { Text("Yêu cầu mới") }
            if (state.admin && state.signedIn) TabRow(selectedTabIndex = state.tab.ordinal, containerColor = MaterialTheme.colorScheme.background) {
                listOf("Chờ duyệt", "Đã duyệt", "Lịch sử").forEachIndexed { index, title ->
                    Tab(selected = state.tab.ordinal == index, onClick = { actions.tab(RequestTab.entries[index]) }, text = { Text(title) })
                }
            }
            if (state.loading) LinearProgressIndicator(Modifier.fillMaxWidth().testTag("requests-loading"))
            if (state.sheet == null) state.message?.let { AgentMessage(it, Modifier.padding(16.dp)) }
            LazyColumn(state = listState, modifier = Modifier.weight(1f).testTag("requests-list"), contentPadding = PaddingValues(vertical = 8.dp)) {
                if (state.admin && !state.signedIn) item("account-entry") {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text("Đăng nhập quản trị để xem và duyệt yêu cầu.")
                        OutlinedButton(onClick = actions.account) { Text("Tài khoản quản trị") }
                    }
                } else if (visible.isEmpty() && !state.loading) item("empty") {
                    Column(Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(if (state.admin) "Không có yêu cầu trong mục này." else "Chưa có yêu cầu cài ứng dụng.", style = MaterialTheme.typography.titleMedium)
                        if (!state.admin) AgentMessage("Nhấn Yêu cầu mới để chọn ứng dụng, hoặc chia sẻ link từ Google Play sang Agent.")
                    }
                }
                items(visible, key = { it.id }, contentType = { "request" }) { row ->
                    AgentAppRow(
                        name = row.appName, subtitle = if (state.admin) row.deviceLabel else row.developer,
                        packageName = row.packageName, detail = row.reason,
                        modifier = Modifier.testTag("request-${row.id}"), enabled = !state.working,
                        trailing = { AgentStatusBadge(if (row.installedAt != null) "Đã cài" else requestStatusLabel(row.status), if (row.installedAt != null) "installed" else row.status) },
                        onClick = { actions.details(row) },
                    )
                    HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                }
            }
            if (state.admin && state.signedIn && (state.page > 1 || state.requests.size == 100)) Row(
                Modifier.fillMaxWidth().padding(horizontal = 12.dp), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
            ) {
                TextButton(onClick = { actions.page(-1) }, enabled = !state.loading && state.page > 1) { Text("Trước") }
                Text("Trang ${state.page}", style = MaterialTheme.typography.bodyMedium)
                TextButton(onClick = { actions.page(1) }, enabled = !state.loading && state.requests.size == 100) { Text("Sau") }
            }
        }
    }
    state.sheet?.let { sheet ->
        val working by rememberUpdatedState(state.working)
        val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true, confirmValueChange = { !working })
        ModalBottomSheet(onDismissRequest = actions.dismiss, sheetState = sheetState, containerColor = MaterialTheme.colorScheme.surface) {
            key(when (sheet) { is AppRequestSheet.Details -> "details-${sheet.request.id}"; is AppRequestSheet.Form -> "form-${sheet.app.packageName}"; else -> "picker" }) {
                Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).imePadding().padding(horizontal = 20.dp).padding(bottom = 24.dp),
                    verticalArrangement = Arrangement.spacedBy(12.dp)) {
                    when (sheet) {
                        AppRequestSheet.Picker -> AppPicker(state, actions)
                        is AppRequestSheet.Form -> RequestForm(sheet.app, state, actions)
                        is AppRequestSheet.Details -> RequestDetails(sheet.request, state, actions)
                    }
                }
            }
        }
    }
}

@Composable
private fun AppPicker(state: AppRequestsState, actions: AppRequestsActions) {
    val keyboard = LocalSoftwareKeyboardController.current
    fun search() { keyboard?.hide(); actions.search() }
    Text("Tìm ứng dụng", style = MaterialTheme.typography.titleLarge)
    OutlinedTextField(value = state.query, onValueChange = actions.query, label = { Text("Tên ứng dụng") }, singleLine = true,
        modifier = Modifier.fillMaxWidth().testTag("app-query"), keyboardOptions = KeyboardOptions(imeAction = ImeAction.Search),
        keyboardActions = KeyboardActions(onSearch = { search() }))
    Button(onClick = { search() }, enabled = !state.searching, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text(if (state.searching) "Đang tìm…" else "Tìm kiếm") }
    OutlinedButton(onClick = actions.openPlay, modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Tìm trong Google Play") }
    AgentMessage("Trong Google Play: mở app → Chia sẻ → MDMesh · Yêu cầu cài app.")
    AgentMessage("Google Play trên máy sẽ kiểm tra khả năng cài ứng dụng.")
    if (state.searching) LinearProgressIndicator(Modifier.fillMaxWidth())
    state.searchMessage?.let { AgentMessage(it, error = state.searchResults.isEmpty()) }
    if (state.searchResults.isNotEmpty()) Text("${state.searchResults.size} kết quả", style = MaterialTheme.typography.labelLarge)
    state.searchResults.forEach { app -> key(app.packageName) {
        AgentAppRow(app.name, app.developer, app.packageName, Modifier.testTag("catalog-${app.packageName}"), onClick = { actions.choose(app) })
        HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
    } }
    TextButton(onClick = actions.dismiss, modifier = Modifier.fillMaxWidth()) { Text("Đóng") }
}

@Composable
private fun RequestForm(app: CatalogApp, state: AppRequestsState, actions: AppRequestsActions) {
    Text("Yêu cầu cài ứng dụng", style = MaterialTheme.typography.titleLarge)
    AgentAppRow(app.name, app.developer, app.packageName)
    if (app.name == app.packageName) AgentMessage("Chưa lấy được tên ứng dụng từ Google Play.")
    OutlinedTextField(value = state.reason, onValueChange = actions.reason, label = { Text("Lý do cần cài ứng dụng") },
        enabled = !state.working, minLines = 2, maxLines = 5, modifier = Modifier.fillMaxWidth().testTag("request-reason"))
    AgentMessage("Quản trị viên sẽ xem xét yêu cầu. Bạn tự cài qua Google Play sau khi được duyệt.")
    state.message?.let { AgentMessage(it, error = true) }
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        TextButton(onClick = actions.dismiss, enabled = !state.working, modifier = Modifier.weight(1f)) { Text("Hủy") }
        Button(onClick = actions.submit, enabled = !state.working && state.reason.isNotBlank(), modifier = Modifier.weight(2f).heightIn(min = 48.dp)) {
            Text(if (state.working) "Đang gửi…" else "Gửi yêu cầu")
        }
    }
}

@Composable
private fun RequestDetails(row: AppInstallRequest, state: AppRequestsState, actions: AppRequestsActions) {
    Text(row.appName, style = MaterialTheme.typography.titleLarge)
    Text("Thiết bị yêu cầu: ${row.deviceLabel}", style = MaterialTheme.typography.titleMedium, modifier = Modifier.testTag("request-device"))
    if (row.developer.isNotBlank()) AgentMessage(row.developer)
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
        AgentStatusBadge(requestStatusLabel(row.status), row.status)
        if (row.createdAt > 0) AgentMessage(requestTime(row.createdAt))
    }
    Text("Lý do", style = MaterialTheme.typography.labelLarge)
    Text(row.reason, style = MaterialTheme.typography.bodyLarge)
    if (row.decisionBy.isNotBlank()) AgentMessage("${row.decisionBy}: ${row.decisionNote.ifBlank { "Không ghi chú" }}")
    if (!state.admin && row.status == "approved") Button(onClick = { actions.openApproved(row) }, enabled = !state.working,
        modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp)) { Text("Mở trong Google Play") }
    if (state.admin && state.signedIn && row.status in setOf("pending", "approved")) {
        if (row.status == "pending") AgentMessage("Duyệt cho thiết bị này. User tự cài trong Google Play; chính sách máy vẫn áp dụng.")
        OutlinedTextField(value = state.note, onValueChange = actions.note, label = { Text("Ghi chú quản trị") }, enabled = !state.working,
            minLines = 2, maxLines = 4, modifier = Modifier.fillMaxWidth().testTag("decision-note"))
        if (row.status == "pending") Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedButton(onClick = { actions.decide(RequestDecision.REJECT) }, enabled = !state.working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Từ chối") }
            Button(onClick = { actions.decide(RequestDecision.APPROVE) }, enabled = !state.working, modifier = Modifier.weight(1f).heightIn(min = 48.dp)) { Text("Duyệt") }
        } else OutlinedButton(onClick = { actions.decide(RequestDecision.REVOKE) }, enabled = !state.working,
            modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error)) { Text("Thu hồi") }
    }
    state.message?.let { AgentMessage(it, error = true) }
    if (state.admin && state.signedIn) {
        HorizontalDivider()
        Text("Lịch sử quyết định", style = MaterialTheme.typography.titleMedium)
        when {
            state.historyLoading -> AgentMessage("Đang tải lịch sử…")
            state.historyError != null -> AgentMessage(state.historyError, error = true)
            state.history.isEmpty() -> AgentMessage("Chưa có quyết định.")
            else -> state.history.forEach { audit ->
                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text("${requestStatusLabel(audit.status)} · ${audit.actor}", style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium)
                    AgentMessage(requestTime(audit.createdAt))
                    if (audit.note.isNotBlank()) Text(audit.note, style = MaterialTheme.typography.bodyMedium)
                }
            }
        }
    }
    TextButton(onClick = actions.dismiss, enabled = !state.working, modifier = Modifier.fillMaxWidth()) { Text("Đóng") }
}

private val RequestTimeFormat = DateTimeFormatter.ofPattern("HH:mm dd/MM/yyyy")
@Composable
private fun requestTime(time: Long): String {
    val zone = ZoneId.systemDefault()
    return remember(time, zone) { Instant.ofEpochMilli(time).atZone(zone).format(RequestTimeFormat) }
}

@Preview(showBackground = true, widthDp = 360, heightDp = 760)
@Composable
private fun AdminRequestsPreview() {
    MDMeshTheme {
        AppRequestsScreen(AppRequestsState(admin = true, signedIn = true, requests = listOf(
            AppInstallRequest(1, "com.zing.zalo", "Zalo", deviceName = "Realme v15", deviceNumber = "device-id", status = "pending", reason = "Liên lạc với khách hàng"),
            AppInstallRequest(2, "com.hellochinese", "HelloChinese", deviceName = "Kho Hà Nội", status = "pending", reason = "Học tiếng Trung"),
        )), AppRequestsActions())
    }
}
