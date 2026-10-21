package com.hmdm.persistence;

import com.hmdm.persistence.mapper.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.util.CryptoUtil;
import org.junit.*;
import org.mockito.ArgumentCaptor;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AgentAdminSessionDAOTest {
    AgentAdminSessionMapper mapper;UserMapper users;UnsecureDAO settings;AgentAdminSessionDAO dao;User user;
    @Before public void setup(){
        mapper=mock(AgentAdminSessionMapper.class);users=mock(UserMapper.class);settings=mock(UnsecureDAO.class);dao=new AgentAdminSessionDAO(mapper,users,settings);
        user=new User();user.setId(7);user.setCustomerId(2);user.setPassword("hash");user.setAuthToken("auth-version");user.setLogin("admin");UserRole role=new UserRole();role.setSuperAdmin(true);user.setUserRole(role);when(users.findById(7)).thenReturn(user);
    }
    private AgentAdminRefreshSession register(){
        AgentAdminSessionDAO.Grant grant=dao.issue(user,"installation-fixture-1234");assertNotNull(grant);
        ArgumentCaptor<AgentAdminRefreshSession> row=ArgumentCaptor.forClass(AgentAdminRefreshSession.class);verify(mapper).insert(row.capture());
        verify(mapper).token(eq(CryptoUtil.getSHA256String(grant.refreshToken)),eq(grant.family),anyLong());assertFalse(row.getValue().getCredentialVersion().contains("hash"));
        when(mapper.lockToken(CryptoUtil.getSHA256String(grant.refreshToken))).thenReturn(row.getValue());when(mapper.consume(anyString(),anyLong())).thenReturn(1);return row.getValue();
    }
    @Test public void rotationConsumesOldTokenAndDoesNotExtendAbsoluteLifetime(){
        AgentAdminRefreshSession row=register();long expires=row.getExpiresAt();when(mapper.lockToken(anyString())).thenReturn(row);
        AgentAdminSessionDAO.Grant rotated=dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA");assertNotNull(rotated);assertEquals(expires,rotated.expiresAt);
        verify(mapper).consume(eq(CryptoUtil.getSHA256String("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA")),anyLong());verify(mapper).token(eq(CryptoUtil.getSHA256String(rotated.refreshToken)),eq(row.getId()),anyLong());
    }
    @Test public void replayRevokesWholeFamilyWithoutMintingAnotherToken(){
        AgentAdminRefreshSession row=register();row.setUsedAt(System.currentTimeMillis());when(mapper.lockToken(anyString())).thenReturn(row);clearInvocations(mapper);
        assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));verify(mapper).revoke(eq(row.getId()),anyLong());verify(mapper,never()).token(anyString(),anyString(),anyLong());
    }
    @Test public void changedPasswordRevokesRefreshAndExistingAccessSession(){
        AgentAdminRefreshSession row=register();when(mapper.lockToken(anyString())).thenReturn(row);when(mapper.get(row.getId())).thenReturn(row);user.setPassword("new-password-hash");
        assertFalse(dao.active(row.getId(),user));assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));verify(mapper).revoke(eq(row.getId()),anyLong());
    }
    @Test public void twoFactorSettingChangeRequiresNewAuthentication(){
        AgentAdminRefreshSession row=register();when(mapper.lockToken(anyString())).thenReturn(row);Settings config=new Settings();config.setTwoFactor(true);when(settings.getSettings(2)).thenReturn(config);
        assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));verify(mapper).revoke(eq(row.getId()),anyLong());
    }
    @Test public void expiredRevokedOrLostPermissionCannotRefresh(){
        AgentAdminRefreshSession row=register();when(mapper.lockToken(anyString())).thenReturn(row);row.setExpiresAt(1);assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
        row.setExpiresAt(System.currentTimeMillis()+100000);row.setRevokedAt(1L);assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));row.setRevokedAt(null);user.getUserRole().setSuperAdmin(false);assertNull(dao.refresh("AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA"));
    }
    @Test public void malformedTokenNeverTouchesDatabaseAndOldTokenCanRevokeFamily(){
        assertNull(dao.refresh("not-a-token"));verifyNoInteractions(mapper,users,settings);
        String raw="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";when(mapper.family(CryptoUtil.getSHA256String(raw))).thenReturn("family");dao.revoke(raw);verify(mapper).revoke(eq("family"),anyLong());
    }
}
