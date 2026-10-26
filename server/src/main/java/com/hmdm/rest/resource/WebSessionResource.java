package com.hmdm.rest.resource;

import com.hmdm.persistence.AgentAdminSessionDAO;
import com.hmdm.persistence.domain.User;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.rest.filter.AuthFilter;
import com.hmdm.rest.json.Response;
import javax.inject.*;
import javax.servlet.http.*;
import javax.ws.rs.*;
import javax.ws.rs.core.MediaType;
import javax.ws.rs.core.Context;
import java.util.*;

/** Browser refresh credentials never leave an HttpOnly cookie. */
@Singleton @Path("/public/web-session") @Produces(MediaType.APPLICATION_JSON)
public class WebSessionResource {
    private static final String COOKIE="mdmesh_web_refresh";
    private final AgentAdminSessionDAO sessions;
    private final UserMapper users;
    @Inject public WebSessionResource(AgentAdminSessionDAO sessions,UserMapper users){this.sessions=sessions;this.users=users;}
    private String token(HttpServletRequest request){
        if(request.getCookies()!=null)for(Cookie cookie:request.getCookies())if(COOKIE.equals(cookie.getName()))return cookie.getValue();
        return null;
    }
    private String cookie(HttpServletRequest request,String value,int age){
        return COOKIE+"="+value+"; Path="+request.getContextPath()+"/rest/public/web-session; HttpOnly; SameSite=Strict; Max-Age="+age+(request.isSecure()||"https".equalsIgnoreCase(request.getHeader("X-Forwarded-Proto"))?"; Secure":"");
    }
    private javax.ws.rs.core.Response reply(HttpServletRequest request,HttpServletResponse response,AgentAdminSessionDAO.Grant grant){
        response.addHeader("Set-Cookie",cookie(request,grant==null?"":grant.refreshToken,grant==null?0:(int)((grant.expiresAt-System.currentTimeMillis())/1000)));
        return javax.ws.rs.core.Response.status(grant==null?401:200)
            .entity(grant==null?Response.PERMISSION_DENIED():Response.OK(Collections.singletonMap("authenticated",true)))
            .header("Cache-Control","no-store").build();
    }
    // A non-simple header prevents cross-origin form submissions from rotating or creating sessions.
    private boolean allowed(String header){return "1".equals(header);}
    @POST @Path("/register")
    public javax.ws.rs.core.Response register(@Context HttpServletRequest request,@Context HttpServletResponse response,@HeaderParam("X-MDMesh-Session") String header){
        if(!allowed(header))return javax.ws.rs.core.Response.status(403).build();
        HttpSession old=request.getSession(false);
        if(old==null||old.getAttribute(AuthFilter.twoFactorNeeded)!=null||!(old.getAttribute(AuthFilter.sessionCredentials) instanceof User))return reply(request,response,null);
        Object family=old.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE);
        if(family!=null)return javax.ws.rs.core.Response.status(409).build();
        sessions.revoke(token(request));
        AgentAdminSessionDAO.Grant grant=sessions.issue((User)old.getAttribute(AuthFilter.sessionCredentials),"web_"+UUID.randomUUID().toString());
        if(grant!=null){old.invalidate();attach(request.getSession(true),grant);}
        return reply(request,response,grant);
    }
    private void attach(HttpSession session,AgentAdminSessionDAO.Grant grant){
        session.setAttribute(AuthFilter.sessionCredentials,grant.user);
        session.setAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE,grant.family);
        session.setMaxInactiveInterval(AgentAdminSessionDAO.ACCESS_SECONDS);
    }
    @GET @Path("/status")
    public javax.ws.rs.core.Response status(@Context HttpServletRequest request){
        HttpSession session=request.getSession(false);
        boolean valid=false;
        if(session!=null&&session.getAttribute(AuthFilter.twoFactorNeeded)==null&&session.getAttribute(AuthFilter.sessionCredentials) instanceof User){
            User previous=(User)session.getAttribute(AuthFilter.sessionCredentials);
            User user=users.findById(previous.getId());
            Object family=session.getAttribute(AgentAdminSessionDAO.SESSION_ATTRIBUTE);
            valid=user!=null&&!user.isPasswordReset()&&user.getAuthToken()!=null&&Objects.equals(user.getAuthToken(),previous.getAuthToken())
                &&(family==null||family instanceof String&&sessions.active((String)family,user));
        }
        return javax.ws.rs.core.Response.status(valid?200:401).entity(valid?Response.OK():Response.PERMISSION_DENIED()).header("Cache-Control","no-store").build();
    }
    @POST @Path("/refresh")
    public javax.ws.rs.core.Response refresh(@Context HttpServletRequest request,@Context HttpServletResponse response,@HeaderParam("X-MDMesh-Session") String header){
        if(!allowed(header))return javax.ws.rs.core.Response.status(403).build();
        AgentAdminSessionDAO.Grant grant=sessions.refresh(token(request));
        if(grant!=null){HttpSession old=request.getSession(false);if(old!=null)old.invalidate();attach(request.getSession(true),grant);}
        return reply(request,response,grant);
    }
    @POST @Path("/revoke")
    public javax.ws.rs.core.Response revoke(@Context HttpServletRequest request,@Context HttpServletResponse response,@HeaderParam("X-MDMesh-Session") String header){
        if(!allowed(header))return javax.ws.rs.core.Response.status(403).build();
        sessions.revoke(token(request));
        HttpSession old=request.getSession(false);if(old!=null)old.invalidate();
        response.addHeader("Set-Cookie",cookie(request,"",0));
        return javax.ws.rs.core.Response.ok(Response.OK()).header("Cache-Control","no-store").build();
    }
}
