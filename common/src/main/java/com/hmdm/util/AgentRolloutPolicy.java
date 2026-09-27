package com.hmdm.util;

import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;

/** Pure rollout policy shared by delivery, progress and server-side promote/retry guards. */
public final class AgentRolloutPolicy {
    private AgentRolloutPolicy() { }
    public static boolean selected(AgentRollout r, RolloutDeviceRow d) {
        return "fleet".equals(r.getStage()) || ("canary".equals(r.getStage()) && Boolean.TRUE.equals(d.getCanary()));
    }
    public static String status(AgentRollout r, RolloutDeviceRow d, long now) {
        if (d.getAgentPackageName() != null && !d.getAgentPackageName().equals(r.getPackageName())) return "ineligible";
        if (d.getAgentSignatureChecksum() != null && r.getApkSignatureChecksum() != null
                && !d.getAgentSignatureChecksum().equals(r.getApkSignatureChecksum())) return "ineligible";
        if (d.getAgentVersionCode() != null && r.getApkVersionCode() != null) {
            if (d.getAgentVersionCode() >= r.getApkVersionCode()) return "updated";
        } else if (r.getTargetVersion().equals(d.getAgentVersion())) {
            return "updated"; // bridge for agents predating versionCode reporting
        }
        if (!AgentCapabilityTokens.isAllowed(RolloutProgress.INSTALL_CAPABILITY,
                AgentCapabilityTokens.flatten(d.getCapabilitiesJson()))) return "ineligible";
        String cs = d.getCommandStatus();
        if ("failed".equals(cs) || "unsupported".equals(cs)
                || ("expired".equals(cs) && d.getAttempts() != null && d.getAttempts() >= 3)) return "failed";
        if ("done".equals(cs)) {
            // Installation ACK isn't proof of a running new agent. Give it time to restart/check in.
            return d.getCommandCompletedAt() != null && now - d.getCommandCompletedAt() > 10 * 60_000L
                    ? "failed" : "verifying";
        }
        if ("pending".equals(cs) || "delivered".equals(cs) || "accepted".equals(cs)) return "pending";
        if (Boolean.TRUE.equals(d.getBusy())) return "busy";
        return d.getLastSeen() == null || now - d.getLastSeen() > 15 * 60_000L ? "offline" : "waiting";
    }
    public static boolean shouldEnqueue(AgentRollout r, RolloutDeviceRow d, long now) {
        if (d == null || !selected(r, d)) return false;
        String status = status(r, d, now);
        return "waiting".equals(status) || "offline".equals(status);
    }
}
