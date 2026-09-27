package com.hmdm.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.google.inject.Inject;
import com.google.inject.Singleton;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import com.hmdm.persistence.mapper.RolloutMapper;
import com.hmdm.util.AgentRolloutPolicy;
import org.mybatis.guice.transactional.Transactional;
import java.util.List;

/** Lock the rollout row for each mutation so check-in, retry, promote and cancel cannot race. */
@Singleton
public class AgentRolloutCoordinator {
    private final RolloutMapper mapper;
    @Inject public AgentRolloutCoordinator(RolloutMapper mapper) { this.mapper = mapper; }

    @Transactional
    public void create(AgentRollout r, List<String> canary) {
        List<RolloutDeviceRow> devices = mapper.listCustomerDevices(r.getCustomerId());
        if (devices.isEmpty()) throw new IllegalArgumentException("No devices in this organization.");
        for (String number : canary) {
            if (devices.stream().noneMatch(d -> number.equals(d.getDeviceNumber())))
                throw new IllegalArgumentException("A selected device no longer belongs to this organization.");
        }
        mapper.insertRollout(r);
        mapper.snapshotTargets(r.getId(), r.getCustomerId());
        for (String number : canary) mapper.insertCanary(r.getId(), number);
    }

    @Transactional
    public void reconcile(int customerId, String deviceNumber) {
        AgentRollout active = mapper.findActiveByCustomer(customerId);
        if (active == null) return;
        mapper.lock(active.getId());
        AgentRollout r = mapper.findById(active.getId());
        RolloutDeviceRow d = mapper.target(r.getId(), deviceNumber);
        long now = System.currentTimeMillis();
        if (d == null || !AgentRolloutPolicy.selected(r, d)) return;
        String status = AgentRolloutPolicy.status(r, d, now);
        if ("updated".equals(status)) { mapper.completeTarget(r.getId(), deviceNumber, now); return; }
        if ("ineligible".equals(status)) { mapper.cancelIneligible(r.getId(), deviceNumber, now); return; }
        if (!AgentRolloutPolicy.shouldEnqueue(r, d, now)) return;
        ObjectNode p = new ObjectMapper().createObjectNode();
        p.put("url", r.getApkUrl()); p.put("packageName", r.getPackageName());
        p.put("versionCode", r.getApkVersionCode()); p.put("sha256", r.getApkSha256());
        p.put("runAfterInstall", false);
        mapper.enqueue(r.getId(), deviceNumber, p.toString(), now);
    }

    @Transactional
    public void promote(int customerId, int id) {
        AgentRollout r = owned(customerId, id);
        if (!"canary".equals(r.getStage())) throw new IllegalArgumentException("Rollout is not in canary stage.");
        int updated = 0;
        for (RolloutDeviceRow d : mapper.targets(id)) {
            if (!Boolean.TRUE.equals(d.getCanary())) continue;
            if (!"updated".equals(AgentRolloutPolicy.status(r, d, System.currentTimeMillis())))
                throw new IllegalArgumentException("Every canary device must report the target version before promotion.");
            updated++;
        }
        if (updated == 0) throw new IllegalArgumentException("No successfully updated canary devices.");
        mapper.updateStage(id, "fleet", System.currentTimeMillis());
    }

    @Transactional
    public void retry(int customerId, int id, String deviceNumber) {
        AgentRollout r = owned(customerId, id);
        RolloutDeviceRow d = mapper.target(id, deviceNumber);
        if (d == null || !AgentRolloutPolicy.selected(r, d)
                || !"failed".equals(AgentRolloutPolicy.status(r, d, System.currentTimeMillis()))
                || Boolean.TRUE.equals(d.getBusy())) throw new IllegalArgumentException("Only failed, idle devices can be retried.");
        mapper.retry(id, deviceNumber);
    }

    @Transactional
    public void stop(int customerId, int id, boolean finish) {
        AgentRollout r = owned(customerId, id);
        if (!("fleet".equals(r.getStage()) || "canary".equals(r.getStage())))
            throw new IllegalArgumentException("Rollout is no longer active.");
        if (finish) {
            if (!"fleet".equals(r.getStage())) throw new IllegalArgumentException("Promote to fleet before finishing.");
            for (RolloutDeviceRow d : mapper.targets(id)) {
                String s = AgentRolloutPolicy.status(r, d, System.currentTimeMillis());
                if (!"updated".equals(s) && !"ineligible".equals(s))
                    throw new IllegalArgumentException("Some devices still need this release. Keep the rollout active for offline devices.");
            }
        }
        mapper.updateStage(id, finish ? "done" : "cancelled", System.currentTimeMillis());
        mapper.cancelPending(id, System.currentTimeMillis());
    }
    private AgentRollout owned(int customerId, int id) {
        mapper.lock(id);
        AgentRollout r = mapper.findById(id);
        if (r == null || r.getCustomerId() != customerId) throw new IllegalArgumentException("Rollout not found.");
        return r;
    }
}
