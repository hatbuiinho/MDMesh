package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.notification.PushService;
import com.hmdm.security.SecurityContext;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class NetworkAccessAuthorizationTest {
    NetworkAccessDAO dao;ConfigurationDAO configs;DeviceDAO devices;ConfigReconciler reconciler;PushService push;AgentCommandDAO commands;
    NetworkAccessResource resource;NetworkAccessRequest row;Device device;DesiredConfig doc;
    @Before public void setup(){
        dao=mock(NetworkAccessDAO.class);configs=mock(ConfigurationDAO.class);devices=mock(DeviceDAO.class);reconciler=mock(ConfigReconciler.class);push=mock(PushService.class);commands=mock(AgentCommandDAO.class);
        resource=new NetworkAccessResource(dao,configs,devices,reconciler,push,commands);
        UserRole role=new UserRole();role.setSuperAdmin(true);User user=new User();user.setCustomerId(1);user.setLogin("admin");user.setUserRole(role);SecurityContext.init(user);
        device=new Device();device.setNumber("a");device.setCustomerId(1);device.setConfigurationId(1);when(devices.getDeviceByNumber("a")).thenReturn(device);
        row=new NetworkAccessRequest();row.setId(1);row.setDeviceNumber("a");row.setConfigurationId(1);row.setPolicyHash("policy");row.setPackageName("com.example.chat");row.setDomain("video.example.com");row.setStatus("pending");when(dao.get(1)).thenReturn(row);when(dao.decide(any(),anyString())).thenReturn(1);
        doc=new DesiredConfig();DesiredWebAccess p=new DesiredWebAccess();p.setPolicyHash("policy");p.setMode("BLOCKLIST");p.setScope("ALL");p.setDomains(Arrays.asList("video.example.com"));doc.setWebAccess(p);when(reconciler.currentDocument(device)).thenReturn(doc);
        when(commands.getDeviceCapabilities("a")).thenReturn("{\"device\":[\"networkAccess\"]}");
    }
    @After public void cleanup(){SecurityContext.release();}
    private NetworkAccessResource.Decision approve(String scope){NetworkAccessResource.Decision d=new NetworkAccessResource.Decision();d.action="approve";d.scope=scope;d.duration="FOREVER";return d;}
    @Test public void readOnlyCannotGrantOrReadRequestDetails(){SecurityContext.init(1);assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.decide(1,approve("APP")).getStatus());assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.history(1).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void inaccessibleDeviceCannotBeApproved(){when(devices.getDeviceByNumber("a")).thenReturn(null);assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.decide(1,approve("ALL")).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void unknownAppCannotSilentlyBecomeDeviceWideGrant(){row.setPackageName("");assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,approve("APP")).getStatus());verify(dao,never()).decide(any(),anyString());assertEquals(Response.ResponseStatus.OK,resource.decide(1,approve("ALL")).getStatus());assertEquals("[\"*\"]",row.getGrantPackages());assertNull(row.getExpiresAt());}
    @Test public void appGrantDoesNotModifySharedConfiguration(){assertEquals(Response.ResponseStatus.OK,resource.decide(1,approve("APP")).getStatus());assertEquals("[\"com.example.chat\"]",row.getGrantPackages());verify(configs,never()).updateConfiguration(any());verify(push).notifyDevicesOnUpdate(1);}
    @Test public void changedPolicyAndOldAgentCannotReceiveGrant(){row.setPolicyHash("old");assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,approve("APP")).getStatus());row.setPolicyHash("policy");when(commands.getDeviceCapabilities("a")).thenReturn("{}");assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,approve("APP")).getStatus());verify(dao,never()).decide(any(),anyString());}
    @Test public void groupRequiresCurrentMembership(){NetworkAccessResource.Decision d=approve("GROUP");d.ruleId="group";assertEquals(Response.ResponseStatus.ERROR,resource.decide(1,d).getStatus());NetworkRule r=new NetworkRule();r.setId("group");r.setPackages(Arrays.asList("com.example.chat","com.example.browser"));doc.getWebAccess().setRules(Arrays.asList(r));assertEquals(Response.ResponseStatus.OK,resource.decide(1,d).getStatus());assertEquals(Arrays.asList("com.example.chat","com.example.browser"),NetworkAccessDAO.packages(row.getGrantPackages()));}
}
