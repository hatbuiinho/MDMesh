package com.hmdm.rest.resource;

import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.notification.PushService;
import com.hmdm.security.SecurityContext;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.*;

@Singleton @Path("/private/network-access") @Produces(MediaType.APPLICATION_JSON)
public class NetworkAccessResource {
    private final NetworkAccessDAO dao;
    private final ConfigurationDAO configurations;
    private final DeviceDAO devices;
    private final ConfigReconciler reconciler;
    private final PushService push;
    private final AgentCommandDAO commands;
    @Inject public NetworkAccessResource(NetworkAccessDAO dao,ConfigurationDAO configurations,DeviceDAO devices,ConfigReconciler reconciler,PushService push,AgentCommandDAO commands) {
        this.dao=dao;this.configurations=configurations;this.devices=devices;this.reconciler=reconciler;this.push=push;this.commands=commands;
    }
    private boolean configAccess(int id){return SecurityContext.get().hasPermission("configurations")&&configurations.hasConfigurationAccess(id);}
    private Device accessible(String number) {
        try {return devices.getDeviceByNumber(number);} catch(com.hmdm.security.SecurityException e){return null;}
    }
    @GET @Path("/configuration/{id}") public Response rules(@PathParam("id") int id) {
        return configAccess(id)?Response.OK(dao.rules(id)):Response.PERMISSION_DENIED();
    }
    @PUT @Path("/configuration/{id}") @Consumes(MediaType.APPLICATION_JSON)
    public Response save(@PathParam("id") int id,List<NetworkRule> rules) {
        if(!configAccess(id))return Response.PERMISSION_DENIED();
        String error=validateRules(rules);if(error!=null)return Response.ERROR(error);
        dao.save(id,rules);push.notifyDevicesOnUpdate(id);return Response.OK(rules);
    }
    public static boolean validDomain(String host) {
        if(host==null||host.length()>253||!host.equals(host.toLowerCase(Locale.ROOT))||host.endsWith("."))return false;
        String[] labels=host.split("\\.",-1);
        if(labels.length<2)return false;
        for(String l:labels)if(!l.matches("[a-z0-9](?:[a-z0-9-]{0,61}[a-z0-9])?"))return false;
        return true;
    }
    public static boolean validPackage(String pkg){return pkg!=null&&pkg.length()<=255&&pkg.matches("[A-Za-z0-9_]+(\\.[A-Za-z0-9_]+)+");}
    public static String validateRules(List<NetworkRule> rules) {
        if(rules==null||rules.size()>50)return "Provide at most 50 network rules.";
        Set<String> ids=new HashSet<>();
        for(NetworkRule r:rules){
            if(r==null||r.getId()==null||!r.getId().matches("[A-Za-z0-9_-]{1,80}")||!ids.add(r.getId()))return "Each rule needs a unique ID.";
            if(r.getName()==null||r.getName().trim().isEmpty()||r.getName().length()>120)return "Rule name is required (max 120 characters).";
            if(!Arrays.asList("BLOCKLIST","ALLOWLIST").contains(r.getMode()))return "Invalid network mode.";
            if(!Arrays.asList("ALL","SELECTED").contains(r.getScope()))return "Invalid network scope.";
            if(r.getPackages()==null||r.getPackages().size()>500||r.getPackages().stream().anyMatch(p->!validPackage(p)))return "Invalid apps.";
            if("SELECTED".equals(r.getScope())&&r.getPackages().isEmpty())return "Select at least one app.";
            if(r.getDomains()==null||r.getDomains().size()>500||r.getDomains().stream().anyMatch(h->!validDomain(h)))return "Invalid domains; use hostnames without wildcard, protocol or path.";
            if("ALLOWLIST".equals(r.getMode())&&r.getDomains().isEmpty())return "An allowlist requires domains.";
        }
        return null;
    }
    @GET @Path("/requests") public Response list(@QueryParam("device") String number,@QueryParam("page") @DefaultValue("1") int page) {
        if(page<1||page>100000)return Response.ERROR("Invalid page.");
        Integer customer=SecurityContext.get().getCurrentCustomerId().orElse(null);
        if(customer==null||!SecurityContext.get().hasPermission("edit_devices"))return Response.PERMISSION_DENIED();
        List<NetworkAccessRequest> visible=new ArrayList<>();
        Map<String,DesiredConfig> docs=new HashMap<>();
        Map<String,Boolean> applied=new HashMap<>();
        Map<String,Boolean> supported=new HashMap<>();
        for(NetworkAccessRequest r:dao.list(customer,number,(page-1)*500)) {
            Device device=accessible(r.getDeviceNumber());if(device==null)continue;
            if(!docs.containsKey(device.getNumber())) {
                DesiredConfig doc=reconciler.currentDocument(device);docs.put(device.getNumber(),doc);
                DeviceState state=commands.getState(device.getNumber());
                applied.put(device.getNumber(),doc!=null&&state!=null&&Objects.equals(doc.getRevision(),state.getAppliedConfigRevision()));
                supported.put(device.getNumber(),com.hmdm.util.AgentCapabilityTokens.flatten(commands.getDeviceCapabilities(device.getNumber())).contains("device.networkAccess"));
            }
            DesiredConfig doc=docs.get(device.getNumber());
            r.setSupported(supported.get(device.getNumber()));r.setApplied(applied.get(device.getNumber()));
            String effective=r.getStatus();
            if("approved".equals(effective)||"pending".equals(effective)) {
                if(!Objects.equals(device.getConfigurationId(),r.getConfigurationId())||doc==null||doc.getWebAccess()==null||!Objects.equals(doc.getWebAccess().getPolicyHash(),r.getPolicyHash()))effective="stale";
                else if("approved".equals(effective)&&r.getExpiresAt()!=null&&r.getExpiresAt()<=System.currentTimeMillis())effective="expired";
            }
            r.setEffectiveStatus(effective);visible.add(r);
        }
        return Response.OK(visible);
    }
    @GET @Path("/requests/{id}/history") public Response history(@PathParam("id") int id) {
        NetworkAccessRequest row=dao.get(id);
        if(!SecurityContext.get().hasPermission("edit_devices")||row==null||accessible(row.getDeviceNumber())==null)return Response.PERMISSION_DENIED();
        return Response.OK(dao.auditTrail(id));
    }
    public static class Decision {
        public String action;
        public String duration;
        public String timezone;
        public String scope="APP";
        public String ruleId;
        public String note;
    }
    @POST @Path("/requests/{id}/decision") @Consumes(MediaType.APPLICATION_JSON)
    public Response decide(@PathParam("id") int id,Decision body) {
        if(!SecurityContext.get().hasPermission("edit_devices"))return Response.PERMISSION_DENIED();
        NetworkAccessRequest row=dao.get(id);
        Device device=row==null?null:accessible(row.getDeviceNumber());
        if(device==null)return Response.PERMISSION_DENIED();
        if(body==null||body.action==null||body.note!=null&&body.note.length()>1000)return Response.ERROR("Invalid decision.");
        String expected;
        if("revoke".equals(body.action)) {
            expected="approved";row.setStatus("revoked");
        }else{
            expected="pending";
            if("reject".equals(body.action))row.setStatus("rejected");
            else if("approve".equals(body.action)){
                if(!com.hmdm.util.AgentCapabilityTokens.flatten(commands.getDeviceCapabilities(device.getNumber())).contains("device.networkAccess"))return Response.ERROR("Update the Agent before approving network exceptions.");
                DesiredConfig config=reconciler.currentDocument(device);
                DesiredWebAccess policy=config==null?null:config.getWebAccess();
                if(policy==null||!Objects.equals(device.getConfigurationId(),row.getConfigurationId())||!Objects.equals(policy.getPolicyHash(),row.getPolicyHash()))return Response.ERROR("Policy has changed. Submit a new request.");
                List<String> packages=new ArrayList<>();
                if("APP".equals(body.scope)) {
                    if(!validPackage(row.getPackageName()))return Response.ERROR("Application was not identified. Explicitly select a wider scope or reject.");
                    packages.add(row.getPackageName());
                }else if("GROUP".equals(body.scope)) {
                    NetworkRule selected=policy.getRules().stream().filter(r->Objects.equals(r.getId(),body.ruleId)&&"SELECTED".equals(r.getScope())).findFirst().orElse(null);
                    if(selected==null||!selected.getPackages().contains(row.getPackageName()))return Response.ERROR("Select a network group containing the requesting app.");
                    packages.addAll(selected.getPackages());
                }else if("ALL".equals(body.scope))packages.add("*");
                else return Response.ERROR("Invalid grant scope.");
                Long expires;
                long now=System.currentTimeMillis();
                if("FOREVER".equals(body.duration))expires=null;
                else if("15M".equals(body.duration))expires=now+900000;
                else if("1H".equals(body.duration))expires=now+3600000;
                else if("TODAY".equals(body.duration)) {
                    try{expires=java.time.ZonedDateTime.now(java.time.ZoneId.of(body.timezone)).toLocalDate().plusDays(1).atStartOfDay(java.time.ZoneId.of(body.timezone)).toInstant().toEpochMilli();}
                    catch(Exception e){return Response.ERROR("Select a valid timezone for end of day.");}
                }else return Response.ERROR("Choose an expiry or FOREVER.");
                row.setExpiresAt(expires);row.setGrantPackages(NetworkAccessDAO.encode(packages));row.setStatus("approved");
            }else return Response.ERROR("Invalid action.");
        }
        row.setDecisionBy(SecurityContext.get().getCurrentUserName());row.setDecisionNote(body.note);row.setDecidedAt(System.currentTimeMillis());
        if(dao.decide(row,expected)!=1)return Response.ERROR("This request was already changed. Refresh the list.");
        if(device.getConfigurationId()!=null)push.notifyDevicesOnUpdate(device.getConfigurationId());
        return Response.OK(row);
    }
}
