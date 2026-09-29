package com.hmdm.rest.resource.support;

import com.hmdm.notification.AgentWakeHub;
import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.persistence.UnsecureDAO;
import com.hmdm.persistence.domain.AgentCommand;
import com.hmdm.persistence.domain.Application;
import com.hmdm.persistence.domain.Configuration;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import java.util.Arrays;
import java.util.Collections;

import static org.junit.Assert.assertEquals;
import static org.junit.Assert.assertTrue;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

public class ConfigAppInstallerTest {

    @Test
    public void configurationUpdateQueuesNewIntentForAssignedDevicesOnly() {
        UnsecureDAO unsecureDAO = mock(UnsecureDAO.class);
        AgentCommandDAO commandDAO = mock(AgentCommandDAO.class);
        AgentWakeHub wakeHub = mock(AgentWakeHub.class);
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub);

        Configuration configuration = new Configuration();
        configuration.setCustomerId(7);
        Application app = new Application();
        app.setAction(1);
        app.setPkg("com.example.app");
        app.setVersionCode(12);
        app.setUrl("https://mdm.example/apps/example.apk");

        when(unsecureDAO.getConfigurationById(42)).thenReturn(configuration);
        when(unsecureDAO.getPlainConfigurationApplications(7, 42))
                .thenReturn(Collections.singletonList(app));
        when(commandDAO.listDeviceNumbersByConfigurationId(42))
                .thenReturn(Arrays.asList("device-new", "device-satisfied"));
        when(commandDAO.hasSatisfiedOrOpenMatching(eq("device-new"), eq("app.install"), anyString()))
                .thenReturn(false);
        when(commandDAO.hasSatisfiedOrOpenMatching(eq("device-satisfied"), eq("app.install"), anyString()))
                .thenReturn(true);

        assertEquals(1, installer.enqueueConfigAppsForConfiguration(42));

        ArgumentCaptor<AgentCommand> command = ArgumentCaptor.forClass(AgentCommand.class);
        verify(commandDAO).insert(command.capture());
        assertEquals("device-new", command.getValue().getDeviceNumber());
        assertEquals("app.install", command.getValue().getType());
        assertTrue(command.getValue().getPayload().contains("com.example.app"));
        verify(wakeHub).wake("device-new", "commands");
        verify(wakeHub, never()).wake("device-satisfied", "commands");
    }

    @Test
    public void doesNotRetryAnApkRejectedByTheDeviceSdk() {
        UnsecureDAO unsecureDAO = mock(UnsecureDAO.class);
        AgentCommandDAO commandDAO = mock(AgentCommandDAO.class);
        AgentWakeHub wakeHub = mock(AgentWakeHub.class);
        ConfigAppInstaller installer = new ConfigAppInstaller(unsecureDAO, commandDAO, wakeHub);

        Configuration configuration = new Configuration();
        configuration.setCustomerId(7);
        Application app = new Application();
        app.setAction(1);
        app.setPkg("com.example.too.new");
        app.setVersionCode(99);
        app.setUrl("https://mdm.example/apps/too-new.apk");
        when(unsecureDAO.getConfigurationById(42)).thenReturn(configuration);
        when(unsecureDAO.getPlainConfigurationApplications(7, 42))
                .thenReturn(Collections.singletonList(app));
        when(commandDAO.listDeviceNumbersByConfigurationId(42))
                .thenReturn(Collections.singletonList("android-old"));
        when(commandDAO.hasSdkIncompatibleMatching(eq("android-old"), anyString())).thenReturn(true);

        assertEquals(0, installer.enqueueConfigAppsForConfiguration(42));
        verify(commandDAO, never()).insert(any(AgentCommand.class));
        verify(wakeHub, never()).wake(anyString(), anyString());
    }
}
