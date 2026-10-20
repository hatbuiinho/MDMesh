package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.Device;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.rest.resource.support.ConfigReconciler;
import com.hmdm.util.CryptoUtil;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class NetworkAccessDeviceAuthenticationTest {
    @Test public void bearerCannotReadOrWriteAnotherDevicesRequests(){
        NetworkAccessDAO dao=mock(NetworkAccessDAO.class);AgentCommandDAO commands=mock(AgentCommandDAO.class);UnsecureDAO devices=mock(UnsecureDAO.class);ConfigReconciler reconcile=mock(ConfigReconciler.class);
        when(commands.getDeviceSecretHash("a")).thenReturn(CryptoUtil.getSHA256String("test-device-a-secret"));
        when(commands.getDeviceSecretHash("b")).thenReturn(CryptoUtil.getSHA256String("test-device-b-secret"));
        Device a=new Device();a.setNumber("a");a.setConfigurationId(1);when(devices.getDeviceByNumber("a")).thenReturn(a);
        NetworkAccessAgentResource resource=new NetworkAccessAgentResource(dao,commands,devices,reconcile);
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.list("b","Bearer test-device-a-secret").getStatus());
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.request("b","Bearer test-device-a-secret",null).getStatus());
        verify(dao,never()).deviceRequests("b");verify(dao,never()).submit(any());
        when(dao.deviceRequests("a")).thenReturn(Collections.emptyList());assertEquals(Response.ResponseStatus.OK,resource.list("a","Bearer test-device-a-secret").getStatus());
        DesiredConfig doc=new DesiredConfig();DesiredWebAccess p=new DesiredWebAccess();p.setPolicyHash("current");p.setMode("BLOCKLIST");p.setScope("SELECTED");p.setPackages(Arrays.asList("com.example.chat"));p.setDomains(Arrays.asList("video.example.com"));doc.setWebAccess(p);when(reconcile.currentDocument(a)).thenReturn(doc);
        NetworkAccessAgentResource.Request request=new NetworkAccessAgentResource.Request();request.packageName="com.example.chat";request.domain="video.example.com";request.reason="Need video";request.policyHash="stale";
        assertEquals(Response.ResponseStatus.ERROR,resource.request("a","Bearer test-device-a-secret",request).getStatus());
        request.policyHash="current";request.packageName="com.example.other";assertEquals(Response.ResponseStatus.ERROR,resource.request("a","Bearer test-device-a-secret",request).getStatus());
        request.packageName="com.example.chat";assertEquals(Response.ResponseStatus.OK,resource.request("a","Bearer test-device-a-secret",request).getStatus());verify(dao).submit(any());
    }
}
