package com.hmdm.notification;

import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.persistence.mapper.*;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.util.CryptoUtil;
import org.junit.Test;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class NetworkAdminSubscriptionsTest {
    @Test public void credentialChecksRejectMalformedExpiredAndChangedPassword() {
        NetworkAdminSubscriptionMapper mapper=mock(NetworkAdminSubscriptionMapper.class);
        UserMapper users=mock(UserMapper.class);
        NetworkAdminSubscriptions service=new NetworkAdminSubscriptions(mapper,users,mock(DeviceDAO.class),mock(NetworkAccessDAO.class),mock(ConfigReconciler.class));
        assertNull(service.authenticate("invalid"));verifyNoInteractions(mapper);
        String token="AAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAAA";
        assertNull(service.authenticate(token));
        NetworkAdminSubscription row=new NetworkAdminSubscription();row.setUserId(7);row.setCustomerId(1);row.setCredentialVersion(CryptoUtil.getSHA256String("old-password"));
        when(mapper.find(anyString(),anyLong())).thenReturn(row);
        User user=new User();user.setId(7);user.setCustomerId(1);user.setPassword("changed-password");user.setUserRole(new UserRole());
        when(users.findById(7)).thenReturn(user);
        assertNull(service.authenticate(token));
        user.setPassword("old-password");
        assertNull("Role without edit_devices cannot receive",service.authenticate(token));
    }
    @Test public void socketWakeIsTenantScopedAndContainsNoRequestDetails() {
        NetworkAdminSubscriptions service=mock(NetworkAdminSubscriptions.class);
        NetworkAdminSubscription row=new NetworkAdminSubscription();row.setId(1);row.setCustomerId(4);row.setExpiresAt(System.currentTimeMillis()+10000);
        when(service.authenticate("token")).thenReturn(row);
        NetworkAdminWakeHub hub=new NetworkAdminWakeHub(service);
        javax.websocket.Session session=mock(javax.websocket.Session.class);
        javax.websocket.RemoteEndpoint.Async remote=mock(javax.websocket.RemoteEndpoint.Async.class);
        when(session.isOpen()).thenReturn(true);when(session.getAsyncRemote()).thenReturn(remote);
        assertTrue(hub.register("token",session));hub.signal(5);verifyNoInteractions(remote);
        hub.signal(4);verify(remote).sendText("{\"wake\":\"networkAccess\"}");
        assertFalse(hub.register("invalid",session));
    }
}
