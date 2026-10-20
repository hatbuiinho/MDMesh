package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.util.CryptoUtil;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.*;

@Singleton @Path("/public/agent/v1/network-access/{device}") @Produces(MediaType.APPLICATION_JSON)
public class NetworkAccessAgentResource {
    private final NetworkAccessDAO dao;
    private final AgentCommandDAO commands;
    private final UnsecureDAO devices;
    private final ConfigReconciler reconciler;
    @Inject public NetworkAccessAgentResource(NetworkAccessDAO dao,AgentCommandDAO commands,UnsecureDAO devices,ConfigReconciler reconciler){this.dao=dao;this.commands=commands;this.devices=devices;this.reconciler=reconciler;}
    private Device auth(String number,String auth) {
        if(auth==null||!auth.startsWith("Bearer ")||auth.length()>1024)return null;
        String hash=commands.getDeviceSecretHash(number);
        return hash!=null&&CryptoUtil.constantTimeEquals(hash,CryptoUtil.getSHA256String(auth.substring(7)))?devices.getDeviceByNumber(number):null;
    }
    @GET public Response list(@PathParam("device") String number,@HeaderParam("Authorization") String authorization){
        return auth(number,authorization)==null?Response.PERMISSION_DENIED():Response.OK(dao.deviceRequests(number));
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public static class Request {public String packageName;public String domain;public String policyHash;public String reason;}
    @POST @Consumes(MediaType.APPLICATION_JSON)
    public Response request(@PathParam("device") String number,@HeaderParam("Authorization") String authorization,Request body) {
        Device device=auth(number,authorization);if(device==null)return Response.PERMISSION_DENIED();
        if(body==null||!NetworkAccessResource.validDomain(body.domain)||body.packageName==null||!body.packageName.isEmpty()&&!NetworkAccessResource.validPackage(body.packageName)||body.reason==null||body.reason.trim().isEmpty()||body.reason.length()>1000)return Response.ERROR("Provide a domain and reason (max 1000 characters).");
        DesiredConfig config=reconciler.currentDocument(device);DesiredWebAccess policy=config==null?null:config.getWebAccess();
        if(policy==null||!Objects.equals(policy.getPolicyHash(),body.policyHash))return Response.ERROR("Policy changed. Sync the device and try again.");
        if(!blocked(policy,body.packageName,body.domain))return Response.ERROR("This domain is not blocked by the current policy.");
        long now=System.currentTimeMillis();
        NetworkAccessRequest row=new NetworkAccessRequest();row.setDeviceNumber(number);row.setConfigurationId(device.getConfigurationId());row.setPolicyHash(body.policyHash);row.setDomain(body.domain);row.setPackageName(body.packageName);row.setReason(body.reason.trim());row.setCreatedAt(now);
        int submitted=dao.submit(row);
        if(submitted<0)return Response.ERROR("Too many requests. Try again later.");
        if(submitted>0&&com.hmdm.notification.NetworkAdminWakeHub.INSTANCE!=null)com.hmdm.notification.NetworkAdminWakeHub.INSTANCE.signal(device.getCustomerId());
        return Response.OK(dao.deviceRequests(number));
    }
    private static boolean match(List<String> rules,String host){return rules!=null&&rules.stream().anyMatch(r->host.equals(r)||host.endsWith("."+r));}
    public static boolean blocked(DesiredWebAccess p,String pkg,String host){
        boolean target=pkg.isEmpty()||"ALL".equals(p.getScope())||p.getPackages()!=null&&p.getPackages().contains(pkg);
        if(target&&("BLOCKLIST".equals(p.getMode())&&match(p.getDomains(),host)||"ALLOWLIST".equals(p.getMode())&&!match(p.getDomains(),host)))return true;
        for(NetworkRule r:p.getRules())if((pkg.isEmpty()||"ALL".equals(r.getScope())||r.getPackages().contains(pkg))&&("BLOCKLIST".equals(r.getMode())&&match(r.getDomains(),host)||"ALLOWLIST".equals(r.getMode())&&!match(r.getDomains(),host)))return true;
        return false;
    }
}
