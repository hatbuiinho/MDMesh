package com.hmdm.rest;
import com.hmdm.notification.NetworkAdminWakeHub;
import javax.websocket.*;
import javax.websocket.server.*;
import java.util.*;
/** Only a wake hint; details and decisions always require a separate admin session. */
@ServerEndpoint(value="/agent/ws/admin-notifications",configurator=NetworkAdminWakeEndpoint.Auth.class)
public class NetworkAdminWakeEndpoint {
    public static class Auth extends ServerEndpointConfig.Configurator {
        @Override public void modifyHandshake(ServerEndpointConfig config,HandshakeRequest request,HandshakeResponse response){
            String token=null;
            for(Map.Entry<String,List<String>> e:request.getHeaders().entrySet())if(e.getKey().equalsIgnoreCase("Authorization")&&!e.getValue().isEmpty()){
                String value=e.getValue().get(0);if(value.startsWith("Bearer "))token=value.substring(7);
            }
            if(token==null)config.getUserProperties().remove("token");else config.getUserProperties().put("token",token);
        }
    }
    @OnOpen public void open(Session session){
        Object token=session.getUserProperties().get("token");NetworkAdminWakeHub hub=NetworkAdminWakeHub.INSTANCE;
        if(!(token instanceof String)||hub==null||!hub.register((String)token,session))try{session.close(new CloseReason(CloseReason.CloseCodes.VIOLATED_POLICY,"unauthorized"));}catch(Exception ignored){}
    }
    @OnClose public void close(Session session){if(NetworkAdminWakeHub.INSTANCE!=null)NetworkAdminWakeHub.INSTANCE.unregister(session);}
    @OnError public void error(Session session,Throwable error){close(session);}
}
