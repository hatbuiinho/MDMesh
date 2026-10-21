package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentAdminSessionDAO;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.persistence.domain.User;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.json.Response;
import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.*;
import java.util.*;

/** Agent sessions use normal permission-checked servlet cookies, renewed by a separate refresh credential. */
@Singleton @Path("/public/agent-admin-session") @Produces(MediaType.APPLICATION_JSON)
public class AgentAdminSessionResource {
    private final AgentAdminSessionDAO sessions;private final UserMapper users;
    @Inject public AgentAdminSessionResource(AgentAdminSessionDAO sessions,UserMapper users){this.sessions=sessions;this.users=users;}
    private User authenticated(HttpServletRequest request){
        HttpSession session=request.getSession(false);
        if(session==null||session.getAttribute(AuthFilter.twoFactorNeeded)!=null)return null;
        Object stored=session.getAttribute(AuthFilter.sessionCredentials);if(!(stored instanceof User))return null;
        User previous=(User)stored,user=users.findById(previous.getId());
        if(!AgentAdminSessionDAO.permitted(user)||user.getAuthToken()==null||!Objects.equals(user.getAuthToken(),previous.getAuthToken()))return null;
        Object family=session.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE);
        return family==null||family instanceof String&&sessions.active((String)family,user)?user:null;
    }
    private javax.ws.rs.core.Response denied(){return javax.ws.rs.core.Response.status(401).entity(Response.PERMISSION_DENIED()).build();}
    private javax.ws.rs.core.Response ok(Object data){return javax.ws.rs.core.Response.ok(Response.OK(data)).header("Cache-Control","no-store").header("Pragma","no-cache").build();}
    public static class Registration {public String installationId;}
    @POST @Path("/register") @Consumes(MediaType.APPLICATION_JSON)
    public javax.ws.rs.core.Response register(@Context HttpServletRequest request,Registration body){
        User user=authenticated(request);if(user==null)return denied();
        if(request.getSession(false).getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE)!=null)
            return javax.ws.rs.core.Response.status(409).entity(Response.ERROR("This session already has a refresh family. Use refresh or sign in again.")).build();
        AgentAdminSessionDAO.Grant grant;
        try{grant=sessions.issue(user,body==null?null:body.installationId);}catch(IllegalArgumentException e){return javax.ws.rs.core.Response.status(400).entity(Response.ERROR(e.getMessage())).build();}
        if(grant==null)return denied();
        request.getSession(false).invalidate();attach(request.getSession(true),grant);return ok(grant.response());
    }
    private void attach(HttpSession session,AgentAdminSessionDAO.Grant grant){
        session.setAttribute(AuthFilter.sessionCredentials,grant.user);session.removeAttribute(AuthFilter.twoFactorNeeded);
        session.setAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE,grant.family);session.setMaxInactiveInterval(AgentAdminSessionDAO.ACCESS_SECONDS);
    }
    private String token(String header){return header!=null&&header.startsWith("Bearer ")?header.substring(7):null;}
    @POST @Path("/refresh") public javax.ws.rs.core.Response refresh(@Context HttpServletRequest request,@HeaderParam("Authorization") String header){
        AgentAdminSessionDAO.Grant grant=sessions.refresh(token(header));if(grant==null)return denied();
        HttpSession old=request.getSession(false);if(old!=null)old.invalidate();attach(request.getSession(true),grant);return ok(grant.response());
    }
    @GET @Path("/status") public javax.ws.rs.core.Response status(@Context HttpServletRequest request){return authenticated(request)==null?denied():ok(Collections.singletonMap("authenticated",true));}
    @POST @Path("/revoke") public javax.ws.rs.core.Response revoke(@HeaderParam("Authorization") String header){sessions.revoke(token(header));return ok(Collections.singletonMap("revoked",true));}
}
