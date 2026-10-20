package com.hmdm.notification;
import com.hmdm.persistence.domain.NetworkAdminSubscription;
import javax.inject.*;
import javax.websocket.*;
import java.util.concurrent.ConcurrentHashMap;
@Singleton public class NetworkAdminWakeHub {
    public static volatile NetworkAdminWakeHub INSTANCE;
    private final NetworkAdminSubscriptions subscriptions;
    private final ConcurrentHashMap<Integer,Connection> sessions=new ConcurrentHashMap<>();
    private static class Connection { final NetworkAdminSubscription subscription;final Session session;Connection(NetworkAdminSubscription sub,Session s){subscription=sub;session=s;} }
    @Inject public NetworkAdminWakeHub(NetworkAdminSubscriptions subscriptions){this.subscriptions=subscriptions;INSTANCE=this;}
    public boolean register(String token,Session session){
        NetworkAdminSubscription sub=subscriptions.authenticate(token);if(sub==null)return false;
        Connection previous=sessions.put(sub.getId(),new Connection(sub,session));if(previous!=null)close(previous.session);
        return true;
    }
    public void unregister(Session session){sessions.entrySet().removeIf(e->e.getValue().session==session);}
    public void signal(int customer){
        for(Connection c:sessions.values())if(c.subscription.getCustomerId()==customer&&c.session.isOpen()){
            if(c.subscription.getExpiresAt()<=System.currentTimeMillis()){unregister(c.session);close(c.session);continue;}
            try{c.session.getAsyncRemote().sendText("{\"wake\":\"networkAccess\"}");}catch(Exception ignored){unregister(c.session);close(c.session);}
        }
    }
    private static void close(Session s){try{s.close();}catch(Exception ignored){}}
}
