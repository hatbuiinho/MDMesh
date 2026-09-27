/*
 *
 * Headwind MDM: Open Source Android MDM Software
 * https://h-mdm.com
 *
 * Copyright (C) 2019 Headwind Solutions LLC (http://h-sms.com)
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *       http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 *
 */

package com.hmdm.rest.resource;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentReleaseDAO;
import com.hmdm.persistence.AgentRolloutCoordinator;
import com.hmdm.persistence.domain.AgentRelease;
import com.hmdm.persistence.domain.AgentRollout;
import com.hmdm.persistence.domain.RolloutDeviceRow;
import com.hmdm.persistence.mapper.RolloutMapper;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.AgentRolloutPolicy;
import javax.inject.Inject;
import javax.inject.Named;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/** Release selection is server-authoritative; the client never supplies an APK URL or hash. */
@Singleton
@Path("/private/agent/v1/rollout")
@Produces(MediaType.APPLICATION_JSON)
public class RolloutResource {
    private static final Logger LOG = LoggerFactory.getLogger(RolloutResource.class);
    private final RolloutMapper mapper;
    private final AgentReleaseDAO releases;
    private final AgentRolloutCoordinator coordinator;
    private final AgentWakeHub wake;
    private final String baseUrl;
    @Inject public RolloutResource(RolloutMapper mapper, AgentReleaseDAO releases,
                                   AgentRolloutCoordinator coordinator, AgentWakeHub wake,
                                   @Named("base.url") String baseUrl) {
        this.mapper = mapper; this.releases = releases; this.coordinator = coordinator;
        this.wake = wake; this.baseUrl = baseUrl;
    }
    private Integer customer(boolean edit) {
        if (edit && !SecurityContext.get().hasPermission("edit_devices")) return null;
        return SecurityContext.get().getCurrentCustomerId().orElse(null);
    }
    public static class CreateRolloutRequest {
        public Integer releaseId;
        public boolean allDevices;
        public List<String> canaryDeviceNumbers = new ArrayList<>();
    }
    public static class RetryRequest { public String deviceNumber; }

    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response create(CreateRolloutRequest body) {
        Integer cust = customer(true);
        if (cust == null) return Response.PERMISSION_DENIED();
        if (body == null || body.releaseId == null) return Response.ERROR("Select a registered agent release.");
        AgentRelease release = releases.find(cust, body.releaseId);
        if (release == null) return Response.ERROR("Agent release not found.");
        List<String> canary = body.canaryDeviceNumbers == null ? Collections.emptyList() : body.canaryDeviceNumbers;
        if (!body.allDevices && canary.isEmpty()) return Response.ERROR("Select at least one canary device.");
        if (mapper.findActiveByCustomer(cust) != null) return Response.ERROR("A rollout is already active.");
        AgentRollout r = new AgentRollout();
        r.setCustomerId(cust); r.setTargetVersion(release.getVersionName()); r.setPackageName(release.getPackageName());
        r.setApkVersionCode(release.getVersionCode()); r.setApkSha256(release.getSha256());
        r.setApkSignatureChecksum(release.getSignatureChecksum());
        r.setApkUrl(baseUrl.replaceAll("/+$", "") + "/files/" + release.getFilePath());
        r.setStage(body.allDevices ? "fleet" : "canary");
        r.setCreatedAt(System.currentTimeMillis()); r.setUpdatedAt(r.getCreatedAt());
        try {
            coordinator.create(r, body.allDevices ? Collections.emptyList() : canary);
        } catch (IllegalArgumentException e) { return Response.ERROR(e.getMessage()); }
        catch (Exception e) {
            LOG.warn("Could not create rollout for customer {}", cust, e);
            return Response.ERROR("Unable to create rollout. Refresh to check for another active rollout.");
        }
        wakeTargets(r);
        return Response.OK(view(r));
    }

    @GET @Path("/preview/{releaseId}") public Response preview(@PathParam("releaseId") int releaseId) {
        Integer cust = customer(false);
        if (cust == null) return Response.PERMISSION_DENIED();
        AgentRelease release = releases.find(cust, releaseId);
        if (release == null) return Response.ERROR("Agent release not found.");
        AgentRollout r = new AgentRollout();
        r.setTargetVersion(release.getVersionName()); r.setApkVersionCode(release.getVersionCode());
        r.setPackageName(release.getPackageName()); r.setApkSignatureChecksum(release.getSignatureChecksum());
        List<Map<String, Object>> out = new ArrayList<>();
        for (RolloutDeviceRow d : mapper.listCustomerDevices(cust)) {
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("deviceNumber", d.getDeviceNumber()); v.put("description", d.getDescription()); v.put("agentVersion", d.getAgentVersion());
            v.put("agentVersionCode", d.getAgentVersionCode()); v.put("lastSeen", d.getLastSeen());
            v.put("status", AgentRolloutPolicy.status(r, d, System.currentTimeMillis()));
            v.put("identityVerified", d.getAgentSignatureChecksum() != null && d.getAgentPackageName() != null);
            out.add(v);
        }
        return Response.OK(out);
    }
    @GET @Path("/active") public Response active() {
        Integer cust = customer(false);
        if (cust == null) return Response.PERMISSION_DENIED();
        AgentRollout r = mapper.findActiveByCustomer(cust);
        return Response.OK(r == null ? null : view(r));
    }
    @POST @Path("/{id}/promote") public Response promote(@PathParam("id") int id) {
        Integer cust = customer(true);
        if (cust == null) return Response.PERMISSION_DENIED();
        try { coordinator.promote(cust, id); }
        catch (IllegalArgumentException e) { return Response.ERROR(e.getMessage()); }
        AgentRollout r = mapper.findById(id);
        wakeTargets(r);
        return Response.OK(view(r));
    }
    @POST @Path("/{id}/retry") @Consumes(MediaType.APPLICATION_JSON)
    public Response retry(@PathParam("id") int id, RetryRequest body) {
        Integer cust = customer(true);
        if (cust == null) return Response.PERMISSION_DENIED();
        if (body == null || body.deviceNumber == null) return Response.ERROR("Select a device to retry.");
        try { coordinator.retry(cust, id, body.deviceNumber); }
        catch (IllegalArgumentException e) { return Response.ERROR(e.getMessage()); }
        wake.wake(body.deviceNumber, "commands");
        return Response.OK(view(mapper.findById(id)));
    }
    @POST @Path("/{id}/cancel") public Response cancel(@PathParam("id") int id) { return stop(id, false); }
    @POST @Path("/{id}/finish") public Response finish(@PathParam("id") int id) { return stop(id, true); }
    private Response stop(int id, boolean finish) {
        Integer cust = customer(true);
        if (cust == null) return Response.PERMISSION_DENIED();
        try { coordinator.stop(cust, id, finish); }
        catch (IllegalArgumentException e) { return Response.ERROR(e.getMessage()); }
        return Response.OK();
    }
    private void wakeTargets(AgentRollout r) {
        for (RolloutDeviceRow d : mapper.targets(r.getId())) {
            if (AgentRolloutPolicy.selected(r, d)) wake.wake(d.getDeviceNumber(), "commands");
        }
    }
    private Map<String, Integer> counts() {
        Map<String, Integer> c = new LinkedHashMap<>();
        for (String s : Arrays.asList("total", "updated", "pending", "waiting", "offline", "busy", "verifying", "failed", "ineligible")) c.put(s, 0);
        return c;
    }
    private Map<String, Object> view(AgentRollout r) {
        Map<String, Integer> canary = counts(), fleet = counts();
        List<Map<String, Object>> devices = new ArrayList<>();
        boolean fleetStarted = "fleet".equals(r.getStage()) || "done".equals(r.getStage());
        for (RolloutDeviceRow d : mapper.targets(r.getId())) {
            boolean isCanary = Boolean.TRUE.equals(d.getCanary());
            String status = AgentRolloutPolicy.status(r, d, System.currentTimeMillis());
            Map<String, Integer> cohort = isCanary ? canary : fleet;
            cohort.put("total", cohort.get("total") + 1);
            cohort.put(status, cohort.get(status) + 1);
            Map<String, Object> v = new LinkedHashMap<>();
            v.put("deviceNumber", d.getDeviceNumber()); v.put("description", d.getDescription()); v.put("agentVersion", d.getAgentVersion());
            v.put("agentVersionCode", d.getAgentVersionCode()); v.put("lastSeen", d.getLastSeen());
            v.put("cohort", isCanary ? "canary" : "fleet");
            v.put("status", !isCanary && !fleetStarted ? "not_started" : status);
            v.put("detail", d.getCommandDetail()); v.put("attempts", d.getAttempts());
            v.put("identityVerified", d.getAgentSignatureChecksum() != null && d.getAgentPackageName() != null);
            devices.add(v);
        }
        Map<String, Object> progress = new LinkedHashMap<>();
        progress.put("canary", canary); progress.put("fleet", fleetStarted ? fleet : null);
        Map<String, Object> v = new LinkedHashMap<>();
        v.put("id", r.getId()); v.put("targetVersion", r.getTargetVersion()); v.put("packageName", r.getPackageName());
        v.put("apkVersionCode", r.getApkVersionCode()); v.put("stage", r.getStage());
        v.put("createdAt", r.getCreatedAt()); v.put("updatedAt", r.getUpdatedAt());
        v.put("progress", progress); v.put("devices", devices);
        return v;
    }
}
