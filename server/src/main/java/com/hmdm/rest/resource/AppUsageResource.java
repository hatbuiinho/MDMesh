package com.hmdm.rest.resource;

import com.hmdm.notification.PushService;
import com.hmdm.persistence.AppUsageDAO;
import com.hmdm.persistence.ConfigurationDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AppUsageOverride;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.security.SecurityContext;
import javax.inject.Inject;
import javax.inject.Singleton;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.time.*;
import java.util.*;
import java.util.regex.Pattern;

@Singleton
@Path("/private/app-usage")
@Produces(MediaType.APPLICATION_JSON)
public class AppUsageResource {
    private static final Pattern PKG = Pattern.compile("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");
    private final AppUsageDAO dao;
    private final ConfigurationDAO configurations;
    private final UnsecureDAO unsecure;
    private final PushService push;

    @Inject public AppUsageResource(AppUsageDAO dao, ConfigurationDAO configurations, UnsecureDAO unsecure, PushService push) {
        this.dao=dao; this.configurations=configurations; this.unsecure=unsecure; this.push=push;
    }

    @GET @Path("/configuration/{id}")
    public Response get(@PathParam("id") int id) {
        if (!canAccess(id)) return Response.PERMISSION_DENIED();
        DesiredAppUsage value=dao.desired(id);
        if (value==null) { value=new DesiredAppUsage(); value.setTimezone(ZoneId.systemDefault().getId()); }
        return Response.OK(value);
    }

    @PUT @Path("/configuration/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public Response put(@PathParam("id") int id, DesiredAppUsage body) {
        if (!canAccess(id)) return Response.PERMISSION_DENIED();
        String error=validate(body); if (error!=null) return Response.ERROR(error);
        dao.replace(id, body); push.notifyDevicesOnUpdate(id);
        return Response.OK(dao.desired(id));
    }

    @GET @Path("/report")
    public Response report(@QueryParam("configurationId") Integer configurationId,
                           @QueryParam("from") String from, @QueryParam("to") String to) {
        Integer customer=customer(); if (customer==null) return Response.PERMISSION_DENIED();
        if (!SecurityContext.get().hasPermission("configurations")) return Response.PERMISSION_DENIED();
        if (configurationId!=null && !canAccess(configurationId)) return Response.PERMISSION_DENIED();
        try { LocalDate.parse(from); LocalDate.parse(to); }
        catch (Exception e) { return Response.ERROR("from/to must use YYYY-MM-DD"); }
        return Response.OK(dao.report(customer, configurationId, from, to));
    }

    public static class GrantRequest { public String packageName; public Integer extraMinutes; public Long expiresAt; }
    @POST @Path("/device/{number}/override") @Consumes(MediaType.APPLICATION_JSON)
    public Response grant(@PathParam("number") String number, GrantRequest body) {
        Integer customer=customer(); if (customer==null || !SecurityContext.get().hasPermission("edit_devices")) return Response.PERMISSION_DENIED();
        Device device=unsecure.getDeviceByNumber(number);
        if (device==null || device.getCustomerId()!=customer.intValue()) return Response.PERMISSION_DENIED();
        if (body==null || body.packageName==null || !PKG.matcher(body.packageName).matches() || body.extraMinutes==null || body.extraMinutes<1 || body.extraMinutes>1440)
            return Response.ERROR("Invalid package or extra minutes (1-1440).");
        long expires;
        if (body.expiresAt != null) expires = body.expiresAt;
        else {
            DesiredAppUsage policy = device.getConfigurationId() == null ? null : dao.desired(device.getConfigurationId());
            ZoneId zone = policy == null ? ZoneOffset.UTC : ZoneId.of(policy.getTimezone());
            expires = ZonedDateTime.now(zone).toLocalDate().plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli();
        }
        if (expires<=System.currentTimeMillis()) return Response.ERROR("Override expiry must be in the future.");
        AppUsageOverride row=new AppUsageOverride(); row.setDeviceNumber(number); row.setPackageName(body.packageName);
        row.setExtraMinutes(body.extraMinutes); row.setExpiresAt(expires); row.setCreatedAt(System.currentTimeMillis()); dao.override(row);
        if (device.getConfigurationId()!=null) push.notifyDevicesOnUpdate(device.getConfigurationId());
        return Response.OK();
    }

    private boolean canAccess(int id) {
        return SecurityContext.get().hasPermission("configurations") && configurations.hasConfigurationAccess(id);
    }
    private Integer customer() { return SecurityContext.get().getCurrentCustomerId().orElse(null); }
    private String validate(DesiredAppUsage body) {
        if (body==null) return "Policy is required.";
        try { ZoneId.of(body.getTimezone()); } catch(Exception e) { return "Invalid IANA timezone."; }
        Set<String> packages=new HashSet<String>();
        if (body.getRules()==null) return null;
        for (AppUsageRule r:body.getRules()) {
            if (r==null || r.getPackageName()==null || !PKG.matcher(r.getPackageName()).matches()) return "Invalid package name.";
            if (!packages.add(r.getPackageName())) return "Each package may only appear once.";
            if (r.getDailyLimitMinutes()!=null && (r.getDailyLimitMinutes()<1 || r.getDailyLimitMinutes()>1440)) return "Daily limit must be 1-1440 minutes.";
            if (r.getWarningMinutes()!=null && (r.getWarningMinutes()<0 || r.getWarningMinutes()>120)) return "Warning must be 0-120 minutes.";
            if (!"suspend".equals(r.getAction())) return "Only the suspend action is supported.";
            if (r.getAllowedWindows()!=null) for(AppUsageWindow w:r.getAllowedWindows()) {
                try { LocalTime.parse(w.getFrom()); LocalTime.parse(w.getTo()); } catch(Exception e) { return "Window time must use HH:mm."; }
                if (w.getDays()==null || w.getDays().isEmpty() || w.getDays().stream().anyMatch(d->d<1||d>7)) return "Window days must be 1-7.";
            }
        }
        return null;
    }
}
