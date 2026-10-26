package com.hmdm.rest.resource;

import com.hmdm.persistence.*;
import com.hmdm.persistence.domain.*;
import com.hmdm.rest.json.Response;
import com.hmdm.rest.json.agent.*;
import com.hmdm.rest.resource.support.*;
import com.hmdm.util.CryptoUtil;
import org.junit.Before;
import org.junit.Test;
import org.mockito.InOrder;

import static org.junit.Assert.*;
import static org.mockito.Mockito.*;
import static org.mockito.ArgumentMatchers.*;

public class AgentReenrollmentTest {
    private UnsecureDAO devices;
    private AgentEnrollmentTokenDAO tokens;
    private AgentCommandDAO commands;
    private ConfigAppInstaller installer;
    private AgentResource resource;
    private AgentEnrollRequest request;
    private AgentEnrollmentToken token;

    @Before public void setup() {
        devices = mock(UnsecureDAO.class);
        tokens = mock(AgentEnrollmentTokenDAO.class);
        commands = mock(AgentCommandDAO.class);
        installer = mock(ConfigAppInstaller.class);
        resource = new AgentResource(devices, tokens, commands, installer,
                mock(ConfigReconciler.class), mock(AgentRolloutCoordinator.class), mock(AppUsageDAO.class));
        token = new AgentEnrollmentToken();
        token.setId(1);
        token.setCustomerId(7);
        token.setConfigurationId(99);
        when(tokens.findByToken("token")).thenReturn(token);
        when(tokens.claim(1)).thenReturn(true);
        request = new AgentEnrollRequest();
        request.setEnrollToken("token");
        request.setHardwareId("  hardware-a  ");
    }

    private Device device(String number) {
        Device d = new Device();
        d.setNumber(number);
        d.setCustomerId(7);
        d.setConfigurationId(42);
        return d;
    }

    @Test public void reusesIdentityConfigurationAndRotatesSecret() {
        Device old = device("original");
        when(commands.findDeviceNumberByHardwareId(7, "hardware-a")).thenReturn("original");
        when(devices.getDeviceByNumber("original")).thenReturn(old);
        Response response = resource.enroll(request);
        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        AgentEnrollResponse data = (AgentEnrollResponse) response.getData();
        assertEquals("original", data.getDeviceId());
        verify(commands).updateDeviceSecretHash("original", CryptoUtil.getSHA256String(data.getDeviceSecret()));
        verify(commands).updateHardwareId("original", "hardware-a");
        assertEquals(Integer.valueOf(42), old.getConfigurationId());
        verify(installer).enqueueConfigApps(old);
        verify(devices, never()).createNewDeviceForToken(anyString(), anyInt(), any());
        InOrder order = inOrder(commands);
        order.verify(commands).lockEnrollment(7, "hardware-a");
        order.verify(commands).findDeviceNumberByHardwareId(7, "hardware-a");
    }

    @Test public void unknownHardwareCreatesNewDeviceInTokenCustomer() {
        when(devices.createNewDeviceForToken(anyString(), eq(7), eq(99)))
                .thenAnswer(call -> device(call.getArgument(0)));
        Response response = resource.enroll(request);
        assertEquals(Response.ResponseStatus.OK, response.getStatus());
        verify(commands).findDeviceNumberByHardwareId(7, "hardware-a");
        verify(devices).createNewDeviceForToken(anyString(), eq(7), eq(99));
    }

    @Test public void blankHardwareDoesNotMatchOtherUnidentifiedDevices() {
        request.setHardwareId("   ");
        when(devices.createNewDeviceForToken(anyString(), eq(7), eq(99)))
                .thenAnswer(call -> device(call.getArgument(0)));
        assertEquals(Response.ResponseStatus.OK, resource.enroll(request).getStatus());
        verify(commands, never()).findDeviceNumberByHardwareId(anyInt(), anyString());
        verify(commands, never()).lockEnrollment(anyInt(), anyString());
        verify(commands, never()).updateHardwareId(anyString(), anyString());
    }

    @Test public void rejectedTokenCannotRotateExistingCredentials() {
        when(tokens.claim(1)).thenReturn(false);
        assertEquals(Response.ResponseStatus.ERROR, resource.enroll(request).getStatus());
        verifyNoInteractions(commands, devices, installer);
    }

    @Test public void disabledCreationReleasesToken() {
        assertEquals(Response.ResponseStatus.ERROR, resource.enroll(request).getStatus());
        verify(tokens).release(1);
    }

    @Test public void mismatchedCustomerFailsClosed() {
        when(commands.findDeviceNumberByHardwareId(7, "hardware-a")).thenReturn("other");
        Device other = device("other");
        other.setCustomerId(8);
        when(devices.getDeviceByNumber("other")).thenReturn(other);
        try {
            resource.enroll(request);
            fail("Expected customer check");
        } catch (IllegalStateException expected) {
            verify(commands, never()).updateDeviceSecretHash(anyString(), anyString());
        }
    }
}
