package com.hmdm.rest.resource;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.util.CryptoUtil;
import org.junit.*;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;
public class AppInstallAgentResourceTest {
    AppInstallDAO dao; AgentCommandDAO commands; UnsecureDAO devices; PlayStoreResource play; AppInstallAgentResource resource;
    @Before public void setup(){
        dao=mock(AppInstallDAO.class);commands=mock(AgentCommandDAO.class);devices=mock(UnsecureDAO.class);play=mock(PlayStoreResource.class);
        resource=new AppInstallAgentResource(dao,commands,devices,play);
        when(commands.getDeviceSecretHash("a")).thenReturn(CryptoUtil.getSHA256String("secret-a"));when(commands.getDeviceSecretHash("b")).thenReturn(CryptoUtil.getSHA256String("secret-b"));
        Device d=new Device();d.setNumber("a");d.setConfigurationId(1);d.setCustomerId(1);when(devices.getDeviceByNumber("a")).thenReturn(d);when(dao.deviceRequests("a")).thenReturn(Collections.emptyList());
    }
    private AppInstallAgentResource.Request request(){AppInstallAgentResource.Request r=new AppInstallAgentResource.Request();r.packageName="com.example.chat";r.appName="Untrusted title";r.reason="Need for work";return r;}
    @Test public void deviceSecretCannotReadSearchSubmitOrReportForAnotherDevice(){
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.list("b","Bearer secret-a").getStatus());
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.search("b","Bearer secret-a","chat").getStatus());
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.submit("b","Bearer secret-a",request()).getStatus());
        assertEquals(Response.PERMISSION_DENIED().getStatus(),resource.installed("b","Bearer secret-a",1).getStatus());
        verifyNoInteractions(dao,play);
    }
    @Test public void searchIsAuthenticatedValidatedAndRateLimited(){
        assertEquals(Response.ResponseStatus.ERROR,resource.search("a","Bearer secret-a","x").getStatus());
        when(play.searchCatalog("chat",30)).thenReturn(Response.OK(Collections.emptyList()));
        for(int i=0;i<10;i++)assertEquals(Response.ResponseStatus.OK,resource.search("a","Bearer secret-a","chat").getStatus());
        assertEquals(Response.ResponseStatus.ERROR,resource.search("a","Bearer secret-a","chat").getStatus());verify(play,times(10)).searchCatalog("chat",30);
    }
    @Test public void serverResolvesMetadataInsteadOfTrustingDeviceTitle(){
        Map<String,Object> app=new HashMap<>();app.put("packageName","com.example.chat");app.put("name","Verified chat");app.put("summary","Publisher");
        when(play.searchCatalog("com.example.chat",1)).thenReturn(Response.OK(Arrays.asList(app)));
        assertEquals(Response.ResponseStatus.OK,resource.submit("a","Bearer secret-a",request()).getStatus());
        verify(dao).submit(argThat(r->"Verified chat".equals(r.getAppName())&&"Publisher".equals(r.getDeveloper())&&"a".equals(r.getDeviceNumber())&&r.getConfigurationId()==1));
    }
    @Test public void unavailableCatalogPreservesPackageAsExplicitFallback(){
        when(play.searchCatalog(anyString(),anyInt())).thenReturn(Response.ERROR("offline"));
        assertEquals(Response.ResponseStatus.OK,resource.submit("a","Bearer secret-a",request()).getStatus());
        verify(dao).submit(argThat(r->"com.example.chat".equals(r.getAppName())&&r.getDeveloper()==null));
    }
    @Test public void changedConfigurationInvalidatesOldApprovalInDeviceResponse(){
        AppInstallRequest r=new AppInstallRequest();r.setConfigurationId(2);r.setStatus("approved");when(dao.deviceRequests("a")).thenReturn(Arrays.asList(r));
        resource.list("a","Bearer secret-a");assertEquals("stale",r.getStatus());
    }
    @Test public void invalidRequestsNeverReachStorageOrBridge(){
        AppInstallAgentResource.Request r=request();r.packageName="bad/url";assertEquals(Response.ResponseStatus.ERROR,resource.submit("a","Bearer secret-a",r).getStatus());
        r=request();r.reason=" ";assertEquals(Response.ResponseStatus.ERROR,resource.submit("a","Bearer secret-a",r).getStatus());verifyNoInteractions(dao,play);
    }
}
