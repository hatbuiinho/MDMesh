package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.notification.PushService;
import com.hmdm.security.SecurityContext;
import org.junit.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class AppInstallAuthorizationTest {
    AppInstallDAO dao;DeviceDAO devices;PushService push;AppInstallResource resource;Device device;AppInstallRequest row;
    @Before public void setup(){
        dao=mock(AppInstallDAO.class);devices=mock(DeviceDAO.class);push=mock(PushService.class);resource=new AppInstallResource(dao,devices,push);
        UserRole role=new UserRole();role.setSuperAdmin(true);User u=new User();u.setCustomerId(1);u.setLogin("admin");u.setUserRole(role);SecurityContext.init(u);
        device=new Device();device.setNumber("a");device.setConfigurationId(1);when(devices.getDeviceByNumber("a")).thenReturn(device);
        row=new AppInstallRequest();row.setId(1);row.setDeviceNumber("a");row.setConfigurationId(1);row.setStatus("pending");when(dao.get(1)).thenReturn(row);when(dao.decide(any(),anyString())).thenReturn(1);
    }
    @After public void cleanup(){SecurityContext.release();}
    private AppInstallResource.Decision decision(String action){AppInstallResource.Decision d=new AppInstallResource.Decision();d.action=action;d.note="work";return d;}
    @Test public void readOnlyCannotListDecideOrReadHistory(){SecurityContext.init(1);assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.list(1).getStatus());assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.decide(1,decision("approve")).getStatus());assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.history(1).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void inaccessibleDeviceCannotBeApproved(){when(devices.getDeviceByNumber("a")).thenReturn(null);assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.decide(1,decision("approve")).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void changedConfigurationRequiresNewRequest(){device.setConfigurationId(2);assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,decision("approve")).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void approvalAndRevocationUseCompareAndSetTransitions(){assertEquals(Response.ResponseStatus.OK,resource.decide(1,decision("approve")).getStatus());assertEquals("approved",row.getStatus());verify(dao).decide(row,"pending");assertEquals(Response.ResponseStatus.OK,resource.decide(1,decision("revoke")).getStatus());assertEquals("revoked",row.getStatus());verify(dao).decide(row,"approved");verify(push,times(2)).notifyDevicesOnUpdate(1);}
    @Test public void decisionWakesOnlyTheRequestingDevice() {
        com.hmdm.notification.AgentWakeHub previous=com.hmdm.notification.AgentWakeHub.INSTANCE;
        com.hmdm.notification.AgentWakeHub hub=mock(com.hmdm.notification.AgentWakeHub.class);
        try {
            com.hmdm.notification.AgentWakeHub.INSTANCE=hub;
            assertEquals(Response.ResponseStatus.OK,resource.decide(1,decision("approve")).getStatus());
            verify(hub).wake("a","commands");verifyNoMoreInteractions(hub);
        } finally {com.hmdm.notification.AgentWakeHub.INSTANCE=previous;}
    }
    @Test public void failedDecisionNeverWakesDevice() {
        com.hmdm.notification.AgentWakeHub previous=com.hmdm.notification.AgentWakeHub.INSTANCE;
        com.hmdm.notification.AgentWakeHub hub=mock(com.hmdm.notification.AgentWakeHub.class);
        try {
            com.hmdm.notification.AgentWakeHub.INSTANCE=hub;when(dao.decide(any(),anyString())).thenReturn(0);
            resource.decide(1,decision("approve"));verifyNoInteractions(hub);
        } finally {com.hmdm.notification.AgentWakeHub.INSTANCE=previous;}
    }
    @Test public void concurrentDecisionDoesNotPushFailedUpdate(){when(dao.decide(any(),anyString())).thenReturn(0);assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,decision("approve")).getStatus());verifyNoInteractions(push);}
}
