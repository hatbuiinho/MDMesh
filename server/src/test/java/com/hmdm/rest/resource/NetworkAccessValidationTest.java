package com.hmdm.rest.resource;
import com.hmdm.rest.json.agent.*;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
public class NetworkAccessValidationTest {
    @Test public void domainsCannotContainPathsWildcardsOrMalformedLabels(){
        for(String value:Arrays.asList("https://example.com","*.example.com","foo..com","-bad.example.com","example.com.","x"))assertFalse(value,NetworkAccessResource.validDomain(value));
        assertTrue(NetworkAccessResource.validDomain("api.zalo.video"));
        assertTrue(NetworkAccessResource.validDomain("xn--bcher-kva.example"));
    }
    @Test public void groupsRequireAppsAndAllowlistRequiresHosts(){
        NetworkRule r=new NetworkRule();r.setId("group-1");r.setName("Liên lạc");
        assertNotNull(NetworkAccessResource.validateRules(Arrays.asList(r)));
        r.setPackages(Arrays.asList("com.example.chat"));assertNull(NetworkAccessResource.validateRules(Arrays.asList(r)));
        r.setMode("ALLOWLIST");assertNotNull(NetworkAccessResource.validateRules(Arrays.asList(r)));
        r.setDomains(Arrays.asList("chat.example.com"));assertNull(NetworkAccessResource.validateRules(Arrays.asList(r)));
        assertNotNull(NetworkAccessResource.validateRules(Arrays.asList(r,r)));
    }
    @Test public void requestMustBeBlockedWithinItsAppScope(){
        DesiredWebAccess p=new DesiredWebAccess();p.setMode("OFF");p.setScope("ALL");
        NetworkRule r=new NetworkRule();r.setId("video");r.setName("Video");r.setPackages(Arrays.asList("com.example.chat"));r.setDomains(Arrays.asList("video.example.com"));p.setRules(Arrays.asList(r));
        assertTrue(NetworkAccessAgentResource.blocked(p,"com.example.chat","video.example.com"));
        assertFalse(NetworkAccessAgentResource.blocked(p,"com.example.browser","video.example.com"));
        assertFalse(NetworkAccessAgentResource.blocked(p,"com.example.chat","example.com"));
        assertTrue(NetworkAccessAgentResource.blocked(p,"","video.example.com"));
    }
}
