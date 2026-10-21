package com.mdmesh.agent.apps

import androidx.lifecycle.SavedStateHandle
import androidx.lifecycle.viewModelScope
import kotlinx.coroutines.*
import kotlinx.coroutines.test.*
import org.junit.*
import org.junit.Assert.*
import java.io.IOException

@OptIn(ExperimentalCoroutinesApi::class)
class AppRequestsViewModelTest {
    private val dispatcher = StandardTestDispatcher()
    private val models = mutableListOf<AppRequestsViewModel>()
    private val row = AppInstallRequest(7, "com.example.app", "Work app", deviceName = "Realme v15", deviceNumber = "fixture-id", status = "pending", reason = "Work")
    @Before fun setup() { Dispatchers.setMain(dispatcher) }
    @After fun cleanup() { models.forEach { it.viewModelScope.cancel() }; Dispatchers.resetMain() }
    private class FakeRepository : AppRequestsRepository {
        var session = AgentAdminAccount(true, "account-1")
        var loads = 0; var searches = 0; var histories = 0; var submissions = 0
        var load: suspend (Boolean, Int) -> List<AppInstallRequest> = { _, _ -> emptyList() }
        var lookup: suspend (String) -> List<CatalogApp> = { emptyList() }
        var send: suspend (CatalogApp, String) -> List<AppInstallRequest> = { _, _ -> emptyList() }
        var verify: suspend (Int) -> String = { error("revoked") }
        override fun account() = session
        override suspend fun requests(admin: Boolean, page: Int): List<AppInstallRequest> { loads++; return load(admin, page) }
        override suspend fun search(query: String): List<CatalogApp> { searches++; return lookup(query) }
        override suspend fun submit(app: CatalogApp, reason: String): List<AppInstallRequest> { submissions++; return send(app, reason) }
        override suspend fun decide(id: Int, action: RequestDecision, note: String) = Unit
        override suspend fun history(id: Int): List<AppRequestAudit> { histories++; return emptyList() }
        override suspend fun approvedPackage(id: Int) = verify(id)
    }
    private fun model(repo: FakeRepository, saved: SavedStateHandle = SavedStateHandle()) = AppRequestsViewModel(repo, saved).also { models += it }

    @Test fun typingAndRecompositionInputsDoNotRefetchInventoryOrHistory() = runTest {
        val repo = FakeRepository().apply { load = { _, _ -> listOf(row) } }
        val vm = model(repo); vm.start(true); advanceUntilIdle(); vm.details(row); advanceUntilIdle()
        repeat(30) { vm.note("note $it") }; assertEquals(1, repo.histories); assertEquals(1, repo.loads)
        vm.dismiss(); vm.role(false); advanceUntilIdle(); val count = repo.loads
        vm.picker(); repeat(30) { vm.query("Zalo $it") }; assertEquals(0, repo.searches); assertEquals(count, repo.loads)
        vm.choose(CatalogApp("com.zing.zalo", "Zalo")); repeat(30) { vm.reason("Reason $it") }
        assertEquals(count, repo.loads); assertEquals(0, repo.submissions)
    }
    @Test fun lateAdminResponseCannotPopulatePersonalRole() = runTest {
        val blocked = CompletableDeferred<List<AppInstallRequest>>()
        val personal = row.copy(id = 8, appName = "Personal app")
        val repo = FakeRepository().apply { load = { admin, _ -> if (admin) withContext(NonCancellable) { blocked.await() } else listOf(personal) } }
        val vm = model(repo); vm.start(true); runCurrent(); vm.role(false); runCurrent()
        assertEquals(listOf(personal), vm.state.value.requests)
        blocked.complete(listOf(row)); advanceUntilIdle()
        assertFalse(vm.state.value.admin); assertEquals(listOf(personal), vm.state.value.requests); assertFalse(vm.state.value.loading)
    }
    @Test fun logoutClearsAdminRowsDetailsAndNotes() = runTest {
        val repo = FakeRepository().apply { load = { _, _ -> listOf(row) } }
        val vm = model(repo); vm.start(true); advanceUntilIdle(); vm.details(row); vm.note("private note"); advanceUntilIdle()
        repo.session = AgentAdminAccount(false, "account-2"); vm.foreground(); advanceUntilIdle()
        assertFalse(vm.state.value.signedIn); assertTrue(vm.state.value.requests.isEmpty()); assertNull(vm.state.value.sheet); assertEquals("", vm.state.value.note)
    }
    @Test fun draftSurvivesViewModelRecreationWithoutSendingRequest() = runTest {
        val repo = FakeRepository(); val saved = SavedStateHandle()
        val vm = model(repo, saved); vm.start(false); advanceUntilIdle(); vm.choose(CatalogApp("com.zing.zalo", "Zalo", "Zalo Group")); vm.reason("Liên lạc khách hàng")
        val restored = model(repo, saved); restored.start(false); advanceUntilIdle()
        assertEquals("Liên lạc khách hàng", restored.state.value.reason)
        assertEquals("Zalo", (restored.state.value.sheet as AppRequestSheet.Form).app.name); assertEquals(0, repo.submissions)
    }
    @Test fun shareWithoutMetadataStillNeedsExplicitReasonAndSubmission() = runTest {
        val repo = FakeRepository().apply { lookup = { throw IOException("offline") } }
        val vm = model(repo); vm.start(false, "com.example.app"); advanceUntilIdle()
        val form = vm.state.value.sheet as AppRequestSheet.Form
        assertEquals("com.example.app", form.app.packageName); assertEquals(0, repo.submissions)
        vm.submit(); advanceUntilIdle(); assertEquals(0, repo.submissions); assertNotNull(vm.state.value.message)
    }
    @Test fun leavingSharedLookupCannotLeaveSpinnerOrReopenForm() = runTest {
        val gate = CompletableDeferred<List<CatalogApp>>()
        val repo = FakeRepository().apply { lookup = { withContext(NonCancellable) { gate.await() } } }
        val vm = model(repo); vm.start(false, "com.example.app"); runCurrent()
        assertTrue(vm.state.value.loading)
        vm.picker(); assertFalse(vm.state.value.loading)
        gate.complete(listOf(CatalogApp("com.example.app", "App"))); advanceUntilIdle()
        assertEquals(AppRequestSheet.Picker, vm.state.value.sheet)
    }
    @Test fun doubleTapCannotSendTwiceAndFailedSubmissionRetainsDraft() = runTest {
        val gate = CompletableDeferred<List<AppInstallRequest>>()
        val repo = FakeRepository().apply { send = { _, _ -> gate.await() } }
        val vm = model(repo); vm.start(false); advanceUntilIdle(); vm.choose(CatalogApp("com.example.app", "App")); vm.reason("Work")
        vm.submit(); vm.submit(); runCurrent(); vm.dismiss()
        assertTrue(vm.state.value.working); assertNotNull(vm.state.value.sheet); assertEquals(1, repo.submissions)
        gate.completeExceptionally(IOException("offline")); advanceUntilIdle()
        assertFalse(vm.state.value.working); assertEquals("Work", vm.state.value.reason); assertTrue(vm.state.value.sheet is AppRequestSheet.Form)
    }
    @Test fun notificationActionVerifiesRequestBeforeOpeningExactPackage() = runTest {
        val gate = CompletableDeferred<String>()
        val repo = FakeRepository().apply { verify = { id -> assertEquals(7, id); gate.await() } }
        val vm = model(repo); val effects = mutableListOf<AppRequestEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.start(false); advanceUntilIdle(); vm.openApprovedId(7); runCurrent()
        assertTrue(effects.isEmpty()); assertTrue(vm.state.value.working)
        gate.complete("com.example.app"); advanceUntilIdle()
        assertEquals(listOf(AppRequestEffect.OpenPlay("com.example.app")), effects)
    }
    @Test fun revokedApprovalCannotEmitOpenPlayEffect() = runTest {
        val repo = FakeRepository(); val vm = model(repo); val effects = mutableListOf<AppRequestEffect>()
        backgroundScope.launch(UnconfinedTestDispatcher(testScheduler)) { vm.effects.collect { effects += it } }
        vm.start(false); advanceUntilIdle(); vm.openApproved(row.copy(status = "approved")); advanceUntilIdle()
        assertTrue(effects.isEmpty()); assertEquals("revoked", vm.state.value.message); assertFalse(vm.state.value.working)
    }
    @Test fun pollingDoesNotInterruptAnOpenForm() = runTest {
        val repo = FakeRepository(); val vm = model(repo); vm.start(false); advanceUntilIdle(); val count = repo.loads
        vm.picker(); vm.poll(); advanceUntilIdle(); assertEquals(count, repo.loads)
        vm.dismiss(); vm.poll(); advanceUntilIdle(); assertEquals(count + 1, repo.loads)
    }
    @Test fun namedDeviceNeverDisplaysItsIdentifierAndMissingNameFallsBack() {
        assertEquals("Realme v15", row.deviceLabel)
        assertEquals("fixture-id", row.copy(deviceName = " ").deviceLabel)
        assertEquals("fixture-id", row.copy(deviceName = "null").deviceLabel)
        assertEquals("Chưa xác định", row.copy(deviceName = "", deviceNumber = "").deviceLabel)
    }
}
