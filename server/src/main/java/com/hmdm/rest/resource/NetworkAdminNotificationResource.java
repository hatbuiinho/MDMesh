package com.hmdm.rest.resource;
import com.hmdm.notification.NetworkAdminSubscriptions;
import com.hmdm.rest.json.Response;
import javax.inject.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
@Singleton @Path("/public/network-access-notifications") @Produces(MediaType.APPLICATION_JSON)
public class NetworkAdminNotificationResource {
    private final NetworkAdminSubscriptions subscriptions;
    @Inject public NetworkAdminNotificationResource(NetworkAdminSubscriptions subscriptions){this.subscriptions=subscriptions;}
    private String token(String header){return header!=null&&header.startsWith("Bearer ")?header.substring(7):null;}
    @GET public Response summary(@HeaderParam("Authorization") String header){try{return Response.OK(subscriptions.summary(token(header)));}catch(java.lang.SecurityException e){return Response.PERMISSION_DENIED();}}
    @POST @Path("/revoke") public Response revoke(@HeaderParam("Authorization") String header){subscriptions.revoke(token(header));return Response.OK();}
}
