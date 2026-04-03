package ai.core.cli.session;

import ai.core.agent.Agent;
import ai.core.api.server.session.AgentEventListener;
import ai.core.session.FileSessionPersistence;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.function.Supplier;

/**
 * @author stephen
 */
public class LocalChatSessionManager {
    private static final Logger LOGGER = LoggerFactory.getLogger(LocalChatSessionManager.class);

    private final Supplier<Agent> agentFactory;
    private final boolean autoApproveAll;
    private final ToolPermissionStore permissionStore;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();

    public LocalChatSessionManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this.agentFactory = agentFactory;
        this.autoApproveAll = autoApproveAll;
        this.permissionStore = permissionStore;
    }

    public String createSession(String sessionId) {
        String id = sessionId != null ? sessionId : UUID.randomUUID().toString();
        Agent agent = agentFactory.get();
        var session = new InProcessAgentSession(id, agent, autoApproveAll, permissionStore);
        sessions.put(id, new ChatSession(session));
        LOGGER.info("created local chat session, id={}", id);
        return id;
    }

    public ChatSession getSession(String sessionId) {
        return sessions.get(sessionId);
    }

    public void closeSession(String sessionId) {
        var chatSession = sessions.remove(sessionId);
        if (chatSession != null) {
            chatSession.signalSseClose();
            try {
                chatSession.session.close();
            } catch (Exception e) {
                LOGGER.debug("failed to close session, id={}", sessionId, e);
            }
        }
    }

    public List<SessionPersistence.SessionInfo> listSessions(FileSessionPersistence persistence) {
        return persistence.listSessions();
    }

    public void closeAll() {
        for (var chatSession : sessions.values()) {
            chatSession.signalSseClose();
            try {
                chatSession.session.close();
            } catch (Exception e) {
                LOGGER.debug("failed to close session", e);
            }
        }
        sessions.clear();
    }

    public static class ChatSession {
        public final InProcessAgentSession session;
        private final List<AgentEventListener> listeners = new ArrayList<>();
        private final CountDownLatch sseLatch = new CountDownLatch(1);

        public ChatSession(InProcessAgentSession session) {
            this.session = session;
        }

        public void addListener(AgentEventListener listener) {
            synchronized (this) {
                listeners.add(listener);
                session.onEvent(listener);
            }
        }

        public void removeListener(AgentEventListener listener) {
            synchronized (this) {
                listeners.remove(listener);
            }
        }

        public boolean waitForSseClose(long timeout, TimeUnit unit) throws InterruptedException {
            return sseLatch.await(timeout, unit);
        }

        public void signalSseClose() {
            sseLatch.countDown();
        }
    }
}
