package com.hmdm.notification;

import com.hmdm.persistence.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.agent.DesiredConfig;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.security.SecurityContext;
import com.hmdm.util.CryptoUtil;
import javax.inject.*;
import java.security.SecureRandom;
import java.util.*;

/** Receive-only credentials: never valid for admin sessions, decisions or device check-ins. */
@Singleton
public class NetworkAdminSubscriptions {
    private final NetworkAdminSubscriptionMapper mapper;
    private final UserMapper users;
    private final DeviceDAO devices;
    private final NetworkAccessDAO requests;
    private final ConfigReconciler reconciler;
    @Inject public NetworkAdminSubscriptions(NetworkAdminSubscriptionMapper mapper,UserMapper users,DeviceDAO devices,NetworkAccessDAO requests,ConfigReconciler reconciler){this.mapper=mapper;this.users=users;this.devices=devices;this.requests=requests;this.reconciler=reconciler;}
    public Map<String,Object> register(String installation) {
        if(installation==null||!installation.matches("[a-zA-Z0-9_-]{16,64}"))throw new IllegalArgumentException("Invalid installation.");
        User session=SecurityContext.get().getCurrentUser().orElse(null);
        if(session==null||!SecurityContext.get().hasPermission("edit_devices"))throw new java.lang.SecurityException();
        User user=users.findById(session.getId());
        if(user==null||user.isPasswordReset()||!permitted(user))throw new java.lang.SecurityException();
        byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);
        String token=Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
        NetworkAdminSubscription row=new NetworkAdminSubscription();row.setUserId(user.getId());row.setCustomerId(user.getCustomerId());row.setInstallationId(installation);row.setTokenHash(CryptoUtil.getSHA256String(token));row.setCredentialVersion(credential(user));row.setExpiresAt(System.currentTimeMillis()+30L*24*3600*1000);
        mapper.register(row);
        Map<String,Object> out=new LinkedHashMap<>();out.put("token",token);out.put("expiresAt",row.getExpiresAt());out.put("account",user.getLogin());return out;
    }
    private static String credential(User user){return CryptoUtil.getSHA256String(String.valueOf(user.getPassword()));}
    public NetworkAdminSubscription authenticate(String token) {
        if(token==null||!token.matches("[A-Za-z0-9_-]{43}"))return null;
        NetworkAdminSubscription row=mapper.find(CryptoUtil.getSHA256String(token),System.currentTimeMillis());
        if(row==null)return null;
        User user=users.findById(row.getUserId());
        if(user==null||user.getUserRole()==null||user.isPasswordReset()||user.getCustomerId()!=row.getCustomerId()||!CryptoUtil.constantTimeEquals(credential(user),row.getCredentialVersion()))return null;
        return permitted(user)?row:null;
    }
    private static boolean permitted(User user) {
        return user.getUserRole()!=null&&(user.getUserRole().isSuperAdmin()||user.getUserRole().getPermissions()!=null&&user.getUserRole().getPermissions().stream().anyMatch(p->"edit_devices".equalsIgnoreCase(p.getName())));
    }

    public Map<String,Object> summary(String token) {
        NetworkAdminSubscription row=authenticate(token);if(row==null)throw new java.lang.SecurityException();
        User user=users.findById(row.getUserId());
        SecurityContext previous=SecurityContext.get();
        try {
            SecurityContext.init(user);
            int count=0;int latest=0;
            Map<String,Device> known=new HashMap<>();Map<String,String> hashes=new HashMap<>();
            for(NetworkAccessRequest request:requests.notificationCandidates(row.getCustomerId())) {
                String number=request.getDeviceNumber();
                if(!known.containsKey(number)) {
                    Device device=null;try{device=devices.getDeviceByNumber(number);}catch(com.hmdm.security.SecurityException ignored){}
                    known.put(number,device);
                    if(device!=null){DesiredConfig doc=reconciler.currentDocument(device);hashes.put(number,doc==null||doc.getWebAccess()==null?null:doc.getWebAccess().getPolicyHash());}
                }
                Device device=known.get(number);
                if(device==null||!Objects.equals(device.getConfigurationId(),request.getConfigurationId())||!Objects.equals(hashes.get(number),request.getPolicyHash()))continue;
                count++;latest=Math.max(latest,request.getId());
            }
            Map<String,Object> result=new LinkedHashMap<>();result.put("pendingCount",count);result.put("latestId",latest);return result;
        } finally {restore(previous);}
    }
    static void restore(SecurityContext previous){
        if(previous==null)SecurityContext.release();
        else if(previous.getCurrentUser().isPresent())SecurityContext.init(previous.getCurrentUser().get());
        else if(previous.getCurrentCustomerId().isPresent())SecurityContext.init(previous.getCurrentCustomerId().get());
        else SecurityContext.release();
    }
    public void revoke(String token){if(token!=null)mapper.revoke(CryptoUtil.getSHA256String(token));}
}
