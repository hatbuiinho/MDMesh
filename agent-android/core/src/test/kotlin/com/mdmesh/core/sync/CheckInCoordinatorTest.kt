package com.mdmesh.core.sync

import com.mdmesh.core.command.CommandDispatcher
import com.mdmesh.core.command.CommandHandler
import com.mdmesh.core.command.CommandResults
import com.mdmesh.core.net.ResponseEnvelope
import com.mdmesh.core.usage.AppUsageManager
import com.mdmesh.core.usage.UnsupportedAppUsageManager
import com.mdmesh.core.store.InMemoryConfigStateStore
import com.mdmesh.policy.ApplicationAllowlist
import com.mdmesh.policy.ApplicationAllowlistResult
import com.mdmesh.proto.ConfigApplications
import com.mdmesh.proto.ConfigApplyPayload
import com.mdmesh.proto.AgentCheckInResponse
import com.mdmesh.proto.AppUsageDailyReport
import com.mdmesh.proto.CommandEnvelope
import com.mdmesh.proto.CommandResult
import com.mdmesh.proto.CommandStatus
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.joinAll
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.IOException

class CheckInCoordinatorTest {

    private class TestHandler : CommandHandler {
        override val type: String = "test.cmd"
        override suspend fun handle(command: CommandEnvelope): CommandResult =
            CommandResults.done(command)
    }

    private fun coordinator(
        api: FakeMdmApi,
        pending: PendingResults,
        identityId: String = "dev-1",
        syncStatus: SyncStatus = SyncStatus(),
        appUsage: AppUsageManager = UnsupportedAppUsageManager,
    ): CheckInCoordinator {
        val identity = FakeIdentity(initialId = identityId, initialSecret = "sek-1")
        val eventSink = object : com.mdmesh.core.telemetry.EventSink {
            override fun record(type: String, detail: String?) {}
            override fun drain() = emptyList<com.mdmesh.proto.TelemetryEventDto>()
            override fun restore(events: List<com.mdmesh.proto.TelemetryEventDto>) {}
        }
        val enrollment = EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), eventSink)
        return CheckInCoordinator(
            api = api,
            enrollment = enrollment,
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(listOf(TestHandler())),
            pending = pending,
            stateSource = { null },
            telemetrySource = { null },
            eventSink = eventSink,
            syncStatus = syncStatus,
            appUsage = appUsage,
        )
    }

    private fun command(id: String) =
        CommandEnvelope(commandId = id, issuedAt = "2026-01-01T00:00:00Z", type = "test.cmd")

    @Test
    fun `dispatches returned commands and immediately flushes their results`() = runTest {
        val api = FakeMdmApi().apply {
            checkInResponses.add(ResponseEnvelope(
                status = "OK",
                data = AgentCheckInResponse(commands = listOf(command("c1"))),
            ))
        }
        val pending = PendingResults()

        coordinator(api, pending).runOnce()

        assertEquals(2, api.checkInRequests.size)
        assertEquals("dev-1", api.checkInRequests.first().deviceId)
        assertEquals("must present the per-device secret as a bearer token", "Bearer sek-1", api.checkInAuth.first())
        assertTrue("first cycle sends no acks", api.checkInRequests.first().results.isEmpty())
        // The immediate follow-up carries the command result instead of leaving the UI stale
        // until the periodic check-in.
        val sent = api.checkInRequests[1].results
        assertEquals(1, sent.size)
        assertEquals("c1", sent.first().commandId)
        assertEquals(CommandStatus.DONE, sent.first().status)
        assertTrue(pending.drain().isEmpty())
    }

    @Test
    fun `delivers previously-buffered acks on the next check-in`() = runTest {
        val api = FakeMdmApi()
        val pending = PendingResults().apply {
            add(listOf(CommandResult(commandId = "old", status = CommandStatus.DONE, completedAt = "t")))
        }

        coordinator(api, pending).runOnce()

        val sent = api.checkInRequests.first().results
        assertEquals(1, sent.size)
        assertEquals("old", sent.first().commandId)
        assertTrue("buffer emptied after successful delivery", pending.drain().isEmpty())
    }

    @Test
    fun `includes app usage aggregates in check-in`() = runTest {
        val api = FakeMdmApi()
        val report = AppUsageDailyReport(
            packageName = "com.example.video",
            usageDate = "2026-09-28",
            foregroundMs = 12_000,
            status = "allowed",
        )
        val appUsage = object : AppUsageManager {
            override fun apply(policy: com.mdmesh.proto.AppUsagePolicy?) = "applied"
            override fun evaluateAndReport() = listOf(report)
        }

        coordinator(api, PendingResults(), appUsage = appUsage).runOnce()

        assertEquals(listOf(report), api.checkInRequests.single().appUsage)
    }

    @Test
    fun `reconciles persisted application allowlist on every check-in`() = runTest {
        val store = InMemoryConfigStateStore().apply {
            save(ConfigApplyPayload(
                revision = "r1",
                applications = ConfigApplications(
                    enforceAllowlist = true,
                    allowedPackages = listOf("com.example.allowed"),
                ),
            ))
        }
        var enabled: Boolean? = null
        var allowed: Set<String>? = null
        val allowlist = ApplicationAllowlist { value, packages ->
            enabled = value
            allowed = packages
            ApplicationAllowlistResult(true)
        }
        val api = FakeMdmApi()
        val identity = FakeIdentity(initialId = "dev-1", initialSecret = "sek-1")
        val eventSink = object : com.mdmesh.core.telemetry.EventSink {
            override fun record(type: String, detail: String?) {}
            override fun drain() = emptyList<com.mdmesh.proto.TelemetryEventDto>()
            override fun restore(events: List<com.mdmesh.proto.TelemetryEventDto>) {}
        }
        val enrollment = EnrollmentManager(api, identity, FakeTokenProvider("t"), FakeCapabilitySource(), eventSink)
        val coordinator = CheckInCoordinator(
            api = api,
            enrollment = enrollment,
            identity = identity,
            capabilitySource = FakeCapabilitySource(),
            dispatcher = CommandDispatcher(listOf(TestHandler())),
            pending = PendingResults(),
            stateSource = { null },
            telemetrySource = { null },
            eventSink = eventSink,
            configStateStore = store,
            applicationAllowlist = allowlist,
        )

        coordinator.runOnce()

        assertEquals(true, enabled)
        assertEquals(setOf("com.example.allowed"), allowed)
    }

    @Test
    fun `restores acks when the check-in call fails`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }
        val pending = PendingResults().apply {
            add(listOf(CommandResult(commandId = "old", status = CommandStatus.DONE, completedAt = "t")))
        }

        runCatching { coordinator(api, pending).runOnce() }

        // Acks must survive a failed delivery so they retry next cycle.
        assertEquals(1, pending.drain().size)
    }

    @Test
    fun `concurrent runOnce calls are serialized, never interleaved`() = runTest {
        val api = FakeMdmApi().apply { checkInGate = CompletableDeferred() }
        val coordinator = coordinator(api, PendingResults())

        val first = launch { coordinator.runOnce() }
        val second = launch { coordinator.runOnce() }
        testScheduler.advanceUntilIdle()

        // With one cycle held in flight, the second caller must be parked on the mutex.
        assertEquals("second cycle must wait for the in-flight one", 1, api.checkInRequests.size)
        api.checkInGate!!.complete(Unit)
        joinAll(first, second)
        assertEquals(2, api.checkInRequests.size)
    }

    @Test
    fun `records the failure in SyncStatus and clears it on the next success`() = runTest {
        val api = FakeMdmApi().apply { checkInThrows = IOException("network down") }
        val syncStatus = SyncStatus()
        val coordinator = coordinator(api, PendingResults(), syncStatus = syncStatus)

        runCatching { coordinator.runOnce() }
        assertEquals("network down", syncStatus.lastError.value?.message)
        assertNotNull(syncStatus.lastError.value?.atMillis)

        api.checkInThrows = null
        coordinator.runOnce()
        assertNull(syncStatus.lastError.value)
    }
}
