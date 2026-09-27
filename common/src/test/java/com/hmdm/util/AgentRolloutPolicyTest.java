package com.hmdm.util;

import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import org.junit.Test;
import static org.junit.Assert.*;

public class AgentRolloutPolicyTest {
    private static final long NOW = 10_000_000;
    private AgentRollout rollout() {
        AgentRollout r = new AgentRollout();
        r.setStage("fleet"); r.setTargetVersion("1.0.1"); r.setApkVersionCode(1001);
        r.setPackageName("com.mdmesh.agent"); r.setApkSignatureChecksum("original");
        return r;
    }
    private RolloutDeviceRow device() {
        RolloutDeviceRow d = new RolloutDeviceRow();
        d.setAgentVersion("1.0.0"); d.setAgentVersionCode(1000L); d.setLastSeen(NOW);
        d.setCapabilitiesJson("{\"appManagement\":[\"silentInstall\"]}");
        d.setAgentPackageName("com.mdmesh.agent"); d.setAgentSignatureChecksum("original");
        d.setAttempts(0); return d;
    }
    @Test public void offlineAndExpiredCommandsRemainEligible() {
        RolloutDeviceRow d = device(); d.setLastSeen(1L);
        assertEquals("offline", AgentRolloutPolicy.status(rollout(), d, NOW));
        assertTrue(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
        d.setCommandStatus("expired"); d.setAttempts(2);
        assertTrue(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
        d.setAttempts(3);
        assertEquals("failed", AgentRolloutPolicy.status(rollout(), d, NOW));
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
    }
    @Test public void higherCodeWinsOverVersionNameAndPendingAck() {
        RolloutDeviceRow d = device(); d.setAgentVersionCode(1002L); d.setCommandStatus("delivered");
        assertEquals("updated", AgentRolloutPolicy.status(rollout(), d, NOW));
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
        d.setAgentVersion("1.0.1"); d.setAgentVersionCode(1000L); d.setCommandStatus(null);
        assertTrue(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
    }
    @Test public void legacyAgentUsesVersionName() {
        RolloutDeviceRow d = device(); d.setAgentVersionCode(null); d.setAgentSignatureChecksum(null);
        d.setAgentPackageName(null); d.setAgentVersion("1.0.1");
        assertEquals("updated", AgentRolloutPolicy.status(rollout(), d, NOW));
    }
    @Test public void identityAndCapabilityMismatchCannotInstall() {
        RolloutDeviceRow d = device(); d.setAgentSignatureChecksum("other");
        assertEquals("ineligible", AgentRolloutPolicy.status(rollout(), d, NOW));
        d = device(); d.setAgentPackageName("com.mdmesh.agent.debug");
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
        d = device(); d.setCapabilitiesJson("{}");
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
    }
    @Test public void busyDeviceWaitsThenResumes() {
        RolloutDeviceRow d = device(); d.setBusy(true);
        assertEquals("busy", AgentRolloutPolicy.status(rollout(), d, NOW));
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
        d.setBusy(false);
        assertTrue(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
    }
    @Test public void doneAckRequiresRunningVersionAndFailureNeedsExplicitRetry() {
        RolloutDeviceRow d = device(); d.setCommandStatus("done"); d.setCommandCompletedAt(NOW);
        assertEquals("verifying", AgentRolloutPolicy.status(rollout(), d, NOW));
        assertEquals("failed", AgentRolloutPolicy.status(rollout(), d, NOW + 601_000));
        d.setCommandStatus("failed");
        assertFalse(AgentRolloutPolicy.shouldEnqueue(rollout(), d, NOW));
    }
    @Test public void canaryAndCancelledRolloutsCannotLeakToFleet() {
        AgentRollout r = rollout(); r.setStage("canary"); RolloutDeviceRow d = device();
        assertFalse(AgentRolloutPolicy.shouldEnqueue(r, d, NOW));
        d.setCanary(true); assertTrue(AgentRolloutPolicy.shouldEnqueue(r, d, NOW));
        r.setStage("cancelled"); assertFalse(AgentRolloutPolicy.shouldEnqueue(r, d, NOW));
        r.setStage("done"); assertFalse(AgentRolloutPolicy.shouldEnqueue(r, d, NOW));
    }
}
