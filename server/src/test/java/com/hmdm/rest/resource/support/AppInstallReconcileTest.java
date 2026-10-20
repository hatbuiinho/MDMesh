package com.hmdm.rest.resource.support;
import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.agent.DesiredConfig;
import org.junit.Test;
import java.util.*;
import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
public class AppInstallReconcileTest {
    @Test public void deviceGrantDoesNotLeakAndRevocationChangesRevision() throws Exception {
        UnsecureDAO devices=mock(UnsecureDAO.class);AgentCommandDAO commands=mock(AgentCommandDAO.class);AppUsageDAO usage=mock(AppUsageDAO.class);AppInstallDAO grants=mock(AppInstallDAO.class);
        ConfigReconciler reconcile=new ConfigReconciler(devices,commands,usage);
        java.lang.reflect.Field f=ConfigReconciler.class.getDeclaredField("appInstallDAO");f.setAccessible(true);f.set(reconcile,grants);
        Configuration config=new Configuration();config.setId(1);config.setAppAllowlist(true);config.setAppAllowlistPackages("com.base.app");when(devices.getConfigurationById(1)).thenReturn(config);when(devices.getPlainConfigurationAppsOptimized(1)).thenReturn(Collections.emptyList());
        Device a=new Device();a.setNumber("a");a.setConfigurationId(1);Device b=new Device();b.setNumber("b");b.setConfigurationId(1);
        when(grants.grants("a",1)).thenReturn(Arrays.asList("com.approved.app"));when(grants.grants("b",1)).thenReturn(Collections.emptyList());
        DesiredConfig approved=reconcile.currentDocument(a),other=reconcile.currentDocument(b);
        assertTrue(approved.getApplications().getAllowedPackages().contains("com.approved.app"));assertFalse(other.getApplications().getAllowedPackages().contains("com.approved.app"));
        assertFalse(approved.getApplications().getAllowedPackages().contains("com.android.vending"));assertEquals("com.base.app",config.getAppAllowlistPackages());
        when(grants.grants("a",1)).thenReturn(Collections.emptyList());DesiredConfig revoked=reconcile.currentDocument(a);
        assertFalse(revoked.getApplications().getAllowedPackages().contains("com.approved.app"));assertNotEquals(approved.getRevision(),revoked.getRevision());
    }
}
