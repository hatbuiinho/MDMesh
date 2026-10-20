package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import java.util.*;

@Singleton @Path("/public/agent/v1/app-requests/{device}") @Produces(MediaType.APPLICATION_JSON)
public class AppInstallAgentResource {
    private final AppInstallDAO dao;
    private final AgentCommandDAO commands;
    private final UnsecureDAO devices;
    private final PlayStoreResource play;
    // Bounded, per-device admission limit for the shared Google account. No device profile is forwarded.
    private final Map<String,ArrayDeque<Long>> searches=new LinkedHashMap<>();
    @Inject public AppInstallAgentResource(AppInstallDAO dao,AgentCommandDAO commands,UnsecureDAO devices,PlayStoreResource play){this.dao=dao;this.commands=commands;this.devices=devices;this.play=play;}
    private Device auth(String number,String header){
        if(header==null||!header.startsWith("Bearer ")||header.length()>1024)return null;
        String hash=commands.getDeviceSecretHash(number);
        return hash!=null&&CryptoUtil.constantTimeEquals(hash,CryptoUtil.getSHA256String(header.substring(7)))?devices.getDeviceByNumber(number):null;
    }
    private List<AppInstallRequest> rows(Device device){
        if(device.getConfigurationId()!=null)dao.invalidate(device.getNumber(),device.getConfigurationId());
        List<AppInstallRequest> rows=dao.deviceRequests(device.getNumber());
        for(AppInstallRequest r:rows)if(!Objects.equals(r.getConfigurationId(),device.getConfigurationId())&&Arrays.asList("pending","approved").contains(r.getStatus()))r.setStatus("stale");
        return rows;
    }
    @GET public Response list(@PathParam("device") String number,@HeaderParam("Authorization") String header){Device d=auth(number,header);return d==null?Response.PERMISSION_DENIED():Response.OK(rows(d));}
    private synchronized boolean admit(String device){
        long now=System.currentTimeMillis();
        searches.entrySet().removeIf(e->e.getValue().isEmpty()||e.getValue().peekLast()<now-60000);
        if(!searches.containsKey(device)&&searches.size()>=10000)return false;
        ArrayDeque<Long> times=searches.computeIfAbsent(device,k->new ArrayDeque<>());
        while(!times.isEmpty()&&times.peekFirst()<now-60000)times.removeFirst();
        if(times.size()>=10)return false;times.addLast(now);return true;
    }
    @GET @Path("/search") public Response search(@PathParam("device") String number,@HeaderParam("Authorization") String header,@QueryParam("q") String query){
        if(auth(number,header)==null)return Response.PERMISSION_DENIED();
        if(query==null||query.trim().length()<2||query.length()>200)return Response.ERROR("Nhập từ 2 đến 200 ký tự.");
        if(!admit(number))return Response.ERROR("Bạn tìm quá nhanh. Vui lòng thử lại sau một phút.");
        return play.searchCatalog(query,30);
    }
    @com.fasterxml.jackson.annotation.JsonIgnoreProperties(ignoreUnknown=true)
    public static class Request {public String packageName;public String appName;public String developer;public String reason;}
    static boolean valid(Request b){return b!=null&&NetworkAccessResource.validPackage(b.packageName)&&b.appName!=null&&!b.appName.trim().isEmpty()&&b.appName.length()<=200&&(b.developer==null||b.developer.length()<=300)&&b.reason!=null&&!b.reason.trim().isEmpty()&&b.reason.length()<=1000;}
    @POST @Consumes(MediaType.APPLICATION_JSON) public Response submit(@PathParam("device") String number,@HeaderParam("Authorization") String header,Request body){
        Device d=auth(number,header);if(d==null)return Response.PERMISSION_DENIED();
        if(!valid(body)||d.getConfigurationId()==null)return Response.ERROR("Chọn ứng dụng và nhập lý do (tối đa 1000 ký tự).");
        if(!admit(number))return Response.ERROR("Vui lòng thử gửi lại sau một phút.");
        // Resolve display metadata on the server; a device cannot impersonate another app by title.
        String title=body.packageName, developer=null;
        Response catalog=play.searchCatalog(body.packageName,1);
        if(catalog!=null&&catalog.getStatus()==Response.ResponseStatus.OK){
            com.fasterxml.jackson.databind.JsonNode data=new com.fasterxml.jackson.databind.ObjectMapper().valueToTree(catalog.getData());
            if(data!=null&&data.isArray())for(com.fasterxml.jackson.databind.JsonNode app:data){
                if(body.packageName.equals(app.path("packageName").asText())){
                    String name=app.path("name").asText(body.packageName);title=name.trim().isEmpty()?body.packageName:name.substring(0,Math.min(name.length(),200));
                    String creator=app.path("summary").asText("");developer=creator.substring(0,Math.min(creator.length(),300));break;
                }
            }
        }
        AppInstallRequest r=new AppInstallRequest();r.setDeviceNumber(number);r.setConfigurationId(d.getConfigurationId());r.setPackageName(body.packageName);r.setAppName(title);r.setDeveloper(developer);r.setReason(body.reason.trim());r.setCreatedAt(System.currentTimeMillis());
        if(dao.submit(r)<0)return Response.ERROR("Quá nhiều yêu cầu. Vui lòng thử lại sau.");
        if(com.hmdm.notification.NetworkAdminWakeHub.INSTANCE!=null)com.hmdm.notification.NetworkAdminWakeHub.INSTANCE.signal(d.getCustomerId());
        return Response.OK(rows(d));
    }
    // Advisory inventory report only; never grants installation rights.
    @POST @Path("/{id}/installed") public Response installed(@PathParam("device") String number,@HeaderParam("Authorization") String header,@PathParam("id") int id){
        if(auth(number,header)==null)return Response.PERMISSION_DENIED();
        dao.installed(id,number);return Response.OK();
    }
}
