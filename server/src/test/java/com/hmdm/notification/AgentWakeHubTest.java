package com.hmdm.notification;

import com.hmdm.persistence.AgentCommandDAO;
import org.junit.Test;
import org.mockito.ArgumentCaptor;

import javax.websocket.RemoteEndpoint;
import javax.websocket.SendHandler;
import javax.websocket.SendResult;
import javax.websocket.Session;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

public class AgentWakeHubTest {

    @Test
    public void offlineWakeIsDeliveredWhenDeviceReconnects() {
        AgentWakeHub hub = new AgentWakeHub(mock(AgentCommandDAO.class));
        Session session = mock(Session.class);
        RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAsyncRemote()).thenReturn(remote);

        hub.wake("device-1", "commands");
        verifyNoInteractions(remote);

        hub.register("device-1", session);
        verify(remote).sendText(eq("{\"wake\":\"commands\"}"), any(SendHandler.class));
    }

    @Test
    public void pendingInteractiveWakeIsNotDowngradedByCommandWake() {
        AgentWakeHub hub = new AgentWakeHub(mock(AgentCommandDAO.class));
        Session session = mock(Session.class);
        RemoteEndpoint.Async remote = mock(RemoteEndpoint.Async.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAsyncRemote()).thenReturn(remote);

        hub.wake("device-1", "interactive");
        hub.wake("device-1", "commands");
        hub.register("device-1", session);

        verify(remote).sendText(eq("{\"wake\":\"interactive\",\"ttlSec\":120}"), any(SendHandler.class));
    }

    @Test
    public void asynchronousSendFailureIsRetriedWhenDeviceReconnects() {
        AgentWakeHub hub = new AgentWakeHub(mock(AgentCommandDAO.class));
        Session failedSession = openSession();
        RemoteEndpoint.Async failedRemote = failedSession.getAsyncRemote();
        hub.register("device-1", failedSession);

        hub.wake("device-1", "commands");
        ArgumentCaptor<SendHandler> completion = ArgumentCaptor.forClass(SendHandler.class);
        verify(failedRemote).sendText(eq("{\"wake\":\"commands\"}"), completion.capture());
        completion.getValue().onResult(new SendResult(new IllegalStateException("socket dropped")));

        Session reconnected = openSession();
        hub.register("device-1", reconnected);

        verify(reconnected.getAsyncRemote()).sendText(
                eq("{\"wake\":\"commands\"}"), any(SendHandler.class));
    }

    @Test
    public void synchronousSendFailureIsRetriedWhenDeviceReconnects() {
        AgentWakeHub hub = new AgentWakeHub(mock(AgentCommandDAO.class));
        Session failedSession = openSession();
        RemoteEndpoint.Async failedRemote = failedSession.getAsyncRemote();
        doThrow(new IllegalStateException("socket dropped")).when(failedRemote)
                .sendText(eq("{\"wake\":\"commands\"}"), any(SendHandler.class));
        hub.register("device-1", failedSession);

        hub.wake("device-1", "commands");

        Session reconnected = openSession();
        hub.register("device-1", reconnected);
        verify(reconnected.getAsyncRemote()).sendText(
                eq("{\"wake\":\"commands\"}"), any(SendHandler.class));
    }

    private static Session openSession() {
        Session session = mock(Session.class);
        when(session.isOpen()).thenReturn(true);
        when(session.getAsyncRemote()).thenReturn(mock(RemoteEndpoint.Async.class));
        return session;
    }
}
