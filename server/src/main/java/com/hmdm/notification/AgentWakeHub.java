/*
 * MDMesh agent wake hub: holds live device WebSocket sessions and fans out wake-only signals.
 */
package com.hmdm.notification;

import com.hmdm.persistence.AgentCommandDAO;
import com.hmdm.util.CryptoUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.inject.Inject;
import javax.inject.Singleton;
import javax.websocket.SendHandler;
import javax.websocket.Session;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registry of live agent WebSocket sessions (one per device number) plus the wake fan-out.
 *
 * <p>The wake payload is signal-only ({@code {"wake":"commands"}}) — never a command — so a leaked
 * connection reveals nothing; the device still authenticates its actual sync with its bearer secret.
 * Sessions are authenticated at connect via that same per-device secret (see {@link #validate}).</p>
 *
 * <p>Reached from the container-managed {@code AgentWakeEndpoint} via {@link #INSTANCE} (JSR-356
 * endpoints aren't Guice-instantiated); the hub is an eager singleton so {@code INSTANCE} is set at
 * boot, before any device can connect.</p>
 */
@Singleton
public class AgentWakeHub {

    private static final Logger log = LoggerFactory.getLogger(AgentWakeHub.class);

    /** Static bridge for the container-created WebSocket endpoint. Set in the constructor. */
    public static volatile AgentWakeHub INSTANCE;

    private final AgentCommandDAO commandDAO;
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    /**
     * Wake signals are deliberately tiny and coalescible. Retain one for an offline device so a
     * configuration save racing a WebSocket reconnect is not lost. This is only a latency aid:
     * the regular authenticated check-in remains the durable reconciliation backstop.
     */
    private final Map<String, String> pendingWakes = new ConcurrentHashMap<>();

    @Inject
    public AgentWakeHub(AgentCommandDAO commandDAO) {
        this.commandDAO = commandDAO;
        INSTANCE = this;
    }

    /** Constant-time check of the presented secret against the stored per-device SHA-256 hash. */
    public boolean validate(String deviceNumber, String secret) {
        if (deviceNumber == null || secret == null) {
            return false;
        }
        String expectedHash = commandDAO.getDeviceSecretHash(deviceNumber);
        return expectedHash != null
                && CryptoUtil.constantTimeEquals(CryptoUtil.getSHA256String(secret), expectedHash);
    }

    public synchronized void register(String deviceNumber, Session session) {
        Session previous = sessions.put(deviceNumber, session);
        if (previous != null && previous != session && previous.isOpen()) {
            try { previous.close(); } catch (Exception ignored) { }
        }
        log.debug("Agent wake socket registered for {}", deviceNumber);
        String pending = pendingWakes.remove(deviceNumber);
        if (pending != null) {
            send(session, deviceNumber, pending);
        }
    }

    public void unregister(String deviceNumber, Session session) {
        sessions.remove(deviceNumber, session);
    }

    public boolean isOnline(String deviceNumber) {
        Session s = sessions.get(deviceNumber);
        return s != null && s.isOpen();
    }

    /** Send now when connected, otherwise coalesce one signal for delivery on the next reconnect. */
    public synchronized void wake(String deviceNumber, String wakeKind) {
        Session s = sessions.get(deviceNumber);
        if (s == null || !s.isOpen()) {
            retain(deviceNumber, wakeKind);
            return;
        }
        send(s, deviceNumber, wakeKind);
    }

    private void send(Session session, String deviceNumber, String wakeKind) {
        String payload = "interactive".equals(wakeKind)
                ? "{\"wake\":\"interactive\",\"ttlSec\":120}"
                : "{\"wake\":\"commands\"}";
        try {
            SendHandler completion = result -> {
                if (!result.isOK()) {
                    Throwable error = result.getException();
                    log.warn("Agent wake send failed for {}: {}", deviceNumber,
                            error == null ? "unknown asynchronous send failure" : error.getMessage());
                    retain(deviceNumber, wakeKind);
                }
            };
            session.getAsyncRemote().sendText(payload, completion);
        } catch (Exception e) {
            log.warn("Agent wake send failed for {}: {}", deviceNumber, e.getMessage());
            retain(deviceNumber, wakeKind);
        }
    }

    /** Interactive wake includes command polling and must not be downgraded by a later command wake. */
    private void retain(String deviceNumber, String wakeKind) {
        pendingWakes.merge(deviceNumber, wakeKind,
                (oldKind, newKind) -> "interactive".equals(oldKind) ? oldKind : newKind);
    }
}
