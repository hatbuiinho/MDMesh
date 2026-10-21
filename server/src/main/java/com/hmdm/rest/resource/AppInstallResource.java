package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.security.SecurityContext;
import com.hmdm.notification.PushService;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.*;
@Singleton @Path("/private/app-requests") @Produces(MediaType.APPLICATION_JSON)
public class AppInstallResource {
    private final AppInstallDAO dao; private final DeviceDAO devices; private final PushService push;
    @Inject public AppInstallResource(AppInstallDAO dao,DeviceDAO devices,PushService push){this.dao=dao;this.devices=devices;this.push=push;}
    private Device accessible(String number){try{return devices.getDeviceByNumber(number);}catch(com.hmdm.security.SecurityException e){return null;}}
    @GET public Response list(@QueryParam("page") @DefaultValue("1") int page){
        Integer customer=SecurityContext.get().getCurrentCustomerId().orElse(null);
        if(customer==null||!SecurityContext.get().hasPermission("edit_devices"))return Response.PERMISSION_DENIED();
        if(page<1||page>100000)return Response.ERROR("Invalid page.");
        List<AppInstallRequest> visible=new ArrayList<>();Set<String> checked=new HashSet<>();
        for(AppInstallRequest r:dao.list(customer,(page-1)*100)){Device d=accessible(r.getDeviceNumber());if(d==null)continue;
            if(d.getConfigurationId()!=null&&checked.add(d.getNumber()))dao.invalidate(d.getNumber(),d.getConfigurationId());
            if(!Objects.equals(d.getConfigurationId(),r.getConfigurationId())&&Arrays.asList("pending","approved").contains(r.getStatus()))r.setStatus("stale");visible.add(r);}
        return Response.OK(visible);
    }
    @GET @Path("/{id}/history") public Response history(@PathParam("id") int id){
        AppInstallRequest r=dao.get(id);return !SecurityContext.get().hasPermission("edit_devices")||r==null||accessible(r.getDeviceNumber())==null?Response.PERMISSION_DENIED():Response.OK(dao.history(id));
    }
    public static class Decision {public String action;public String note;}
    @POST @Path("/{id}/decision") @Consumes(MediaType.APPLICATION_JSON) public Response decide(@PathParam("id") int id,Decision body){
        if(!SecurityContext.get().hasPermission("edit_devices"))return Response.PERMISSION_DENIED();
        AppInstallRequest r=dao.get(id);Device d=r==null?null:accessible(r.getDeviceNumber());if(d==null)return Response.PERMISSION_DENIED();
        if(body==null||body.action==null||body.note!=null&&body.note.length()>1000)return Response.ERROR("Invalid decision.");
        if(!Objects.equals(d.getConfigurationId(),r.getConfigurationId()))return Response.ERROR("Cấu hình thiết bị đã thay đổi. Hãy gửi yêu cầu mới.");
        String expected="pending";
        switch(body.action){case "approve":r.setStatus("approved");break;case "reject":r.setStatus("rejected");break;case "revoke":expected="approved";r.setStatus("revoked");break;default:return Response.ERROR("Invalid action.");}
        r.setDecisionBy(SecurityContext.get().getCurrentUserName());r.setDecisionNote(body.note);r.setDecidedAt(System.currentTimeMillis());
        if(dao.decide(r,expected)!=1)return Response.ERROR("Yêu cầu đã thay đổi. Hãy làm mới.");
        push.notifyDevicesOnUpdate(d.getConfigurationId());
        // A wake carries no grant: the enrolled device fetches its own authoritative requests.
        if (com.hmdm.notification.AgentWakeHub.INSTANCE != null)
            com.hmdm.notification.AgentWakeHub.INSTANCE.wake(d.getNumber(), "commands");
        return Response.OK(r);
    }
}
