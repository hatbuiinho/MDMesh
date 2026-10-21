package com.hmdm.persistence;

import com.hmdm.persistence.mapper.AgentAdminSessionMapper;
import com.hmdm.persistence.mapper.UserMapper;
import com.hmdm.persistence.domain.*;
import com.hmdm.util.CryptoUtil;
import javax.inject.*;
import java.security.SecureRandom;
import java.util.*;

/** Opaque rotating tokens; only hashes are retained. Replayed tokens revoke their whole family. */
@Singleton
public class AgentAdminSessionDAO {
    public static final String SESSION_ATTRIBUTE="agentAdminRefreshFamily";
    public static final int ACCESS_SECONDS=15*60;
    public static final long REFRESH_LIFETIME=30L*24*3600*1000;
    private final AgentAdminSessionMapper mapper;
    private final UserMapper users;
    private final UnsecureDAO settings;
    @Inject public AgentAdminSessionDAO(AgentAdminSessionMapper mapper,UserMapper users,UnsecureDAO settings){this.mapper=mapper;this.users=users;this.settings=settings;}
    public static class Grant {
        public final User user;
        public final String family,refreshToken;
        public final long expiresAt;
        Grant(User user,String family,String refreshToken,long expiresAt){this.user=user;this.family=family;this.refreshToken=refreshToken;this.expiresAt=expiresAt;}
        public Map<String,Object> response(){Map<String,Object> out=new LinkedHashMap<>();out.put("refreshToken",refreshToken);out.put("refreshExpiresAt",expiresAt);out.put("accessExpiresAt",System.currentTimeMillis()+ACCESS_SECONDS*1000L);out.put("account",user.getName()==null?user.getLogin():user.getName());return out;}
    }
    private static String token(){byte[] bytes=new byte[32];new SecureRandom().nextBytes(bytes);return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);}
    private static boolean validToken(String token){return token!=null&&token.matches("[A-Za-z0-9_-]{43}");}
    public static boolean permitted(User user){return user!=null&&!user.isPasswordReset()&&user.getUserRole()!=null&&(user.getUserRole().isSuperAdmin()||user.getUserRole().getPermissions()!=null&&user.getUserRole().getPermissions().stream().anyMatch(p->"edit_devices".equalsIgnoreCase(p.getName())));}
    private String credential(User user){Settings config=settings.getSettings(user.getCustomerId());return CryptoUtil.getSHA256String(user.getCustomerId()+":"+user.getPassword()+":"+user.getAuthToken()+":"+(config!=null&&config.isTwoFactor()));}
    @org.mybatis.guice.transactional.Transactional
    public Grant issue(User authenticated,String installation){
        if(installation==null||!installation.matches("[A-Za-z0-9_-]{16,64}"))throw new IllegalArgumentException("Invalid installation.");
        mapper.lockUser(authenticated.getId());
        User user=users.findById(authenticated.getId());
        if(!permitted(user)||user.getAuthToken()==null||!Objects.equals(user.getAuthToken(),authenticated.getAuthToken()))return null;
        long now=System.currentTimeMillis();mapper.replace(user.getId(),installation,now);
        AgentAdminRefreshSession row=new AgentAdminRefreshSession();row.setId(UUID.randomUUID().toString());row.setUserId(user.getId());row.setCustomerId(user.getCustomerId());row.setInstallationId(installation);row.setCredentialVersion(credential(user));row.setExpiresAt(now+REFRESH_LIFETIME);mapper.insert(row);
        String next=token();mapper.token(CryptoUtil.getSHA256String(next),row.getId(),now);return new Grant(user,row.getId(),next,row.getExpiresAt());
    }
    @org.mybatis.guice.transactional.Transactional
    public Grant refresh(String token){
        if(!validToken(token))return null;
        String hash=CryptoUtil.getSHA256String(token);AgentAdminRefreshSession row=mapper.lockToken(hash);long now=System.currentTimeMillis();
        if(row==null||row.getRevokedAt()!=null)return null;
        User user=users.findById(row.getUserId());
        if(row.getUsedAt()!=null||row.getExpiresAt()<=now||!permitted(user)||user.getCustomerId()!=row.getCustomerId()||!CryptoUtil.constantTimeEquals(credential(user),row.getCredentialVersion())){mapper.revoke(row.getId(),now);return null;}
        if(mapper.consume(hash,now)!=1){mapper.revoke(row.getId(),now);return null;}
        String next=token();mapper.token(CryptoUtil.getSHA256String(next),row.getId(),now);
        return new Grant(user,row.getId(),next,row.getExpiresAt());
    }
    public boolean active(String family,User user){
        AgentAdminRefreshSession row=mapper.get(family);
        return row!=null&&row.getRevokedAt()==null&&row.getExpiresAt()>System.currentTimeMillis()&&permitted(user)&&row.getUserId()==user.getId()&&row.getCustomerId()==user.getCustomerId()&&CryptoUtil.constantTimeEquals(row.getCredentialVersion(),credential(user));
    }
    public void revokeFamily(String id){if(id!=null)mapper.revoke(id,System.currentTimeMillis());}
    public void revoke(String token){if(validToken(token))revokeFamily(mapper.family(CryptoUtil.getSHA256String(token)));}
}
