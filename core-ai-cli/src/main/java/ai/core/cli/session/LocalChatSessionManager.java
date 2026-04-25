package ai.core.cli.session;

import ai.core.agent.Agent;
import ai.core.agent.AgentPersistence;
import ai.core.api.server.session.AgentEventListener;
import ai.core.persistence.PersistenceProvider;
import ai.core.session.FileSessionPersistence;
import ai.core.session.InProcessAgentSession;
import ai.core.session.SessionManager;
import ai.core.session.SessionPersistence;
import ai.core.session.ToolPermissionStore;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
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
    private final SessionManager sessionManager;
    private final Map<String, ChatSession> sessions = new ConcurrentHashMap<>();
    private final FileSessionPersistence fileSessionPersistence;
    private final PersistenceProvider persistenceProvider;
    private final String sessionsDir;

    public LocalChatSessionManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore) {
        this(agentFactory, autoApproveAll, permissionStore, null, null, null);
    }

    public LocalChatSessionManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore,
                                   SessionManager sessionManager) {
        this(agentFactory, autoApproveAll, permissionStore, sessionManager, null, null);
    }

    public LocalChatSessionManager(Supplier<Agent> agentFactory, boolean autoApproveAll, ToolPermissionStore permissionStore,
                                   SessionManager sessionManager, PersistenceProvider persistenceProvider, Path workspace) {
        this.agentFactory = agentFactory;
        this.autoApproveAll = autoApproveAll;
        this.permissionStore = permissionStore;
        this.sessionManager = sessionManager;
        this.persistenceProvider = persistenceProvider;
        // Use same session directory as normal mode: ~/.core-ai/sessions/<workspace>/
        this.sessionsDir = getSessionsDir(workspace);
        this.fileSessionPersistence = new FileSessionPersistence(sessionsDir);
        LOGGER.info("LocalChatSessionManager initialized with sessionsDir={}, hasPersistenceProvider={}",
                    sessionsDir, persistenceProvider != null);
    }

    private String getSessionsDir(Path workspace) {
        // Match the path used by CliApp: PathUtils.sessionsDir(workspace)
        // which is: ~/.core-ai/sessions/<workspace>/
        var home = System.getProperty("user.home");
        if (workspace != null) {
            return home + "/.core-ai/sessions/" + workspace.getFileName().toString();
        }
        return home + "/.core-ai/sessions";
    }

    public String createSession(String sessionId) {
        String id = sessionId != null ? sessionId : UUID.randomUUID().toString();
        Agent agent = agentFactory.get();
        var session = new InProcessAgentSession(id, agent, autoApproveAll, permissionStore, true);
        sessions.put(id, new ChatSession(session));
        LOGGER.info("created local chat session, id={}", id);
        return id;
    }

    public ChatSession getSession(String sessionId) {
        var chatSession = sessions.get(sessionId);
        if (chatSession != null) return chatSession;

        // Session not in memory, try to rebuild from persistence
        LOGGER.info("session not found in memory, attempting to rebuild from persistence, sessionId={}", sessionId);
        return rebuildSession(sessionId);
    }

    private ChatSession rebuildSession(String sessionId) {
        try {
            // Check if persistence data exists
            var sessionData = fileSessionPersistence.load(sessionId);
            if (sessionData.isEmpty()) {
                LOGGER.warn("session data not found in persistence, sessionId={}", sessionId);
                return null;
            }

            // Create new agent and session
            Agent agent = agentFactory.get();

            // Restore agent history from persistence data
            // Priority: use persistenceProvider if available (matches normal mode behavior)
            if (persistenceProvider != null) {
                // Use the same persistence provider that was used during normal mode
                agent.load(sessionId);
                LOGGER.debug("agent history restored via persistenceProvider, sessionId={}", sessionId);
            } else {
                // Fallback: manually restore messages from serialized data
                restoreAgentHistory(agent, sessionId, sessionData.get());
            }

            // Use skipLoad=true because we already loaded the agent above
            var session = new InProcessAgentSession(sessionId, agent, autoApproveAll, permissionStore, true);
            var chatSession = new ChatSession(session);
            sessions.put(sessionId, chatSession);

            LOGGER.info("session rebuilt successfully from persistence, sessionId={}", sessionId);
            return chatSession;
        } catch (Exception e) {
            LOGGER.warn("failed to rebuild session from persistence, sessionId={}, error={}", sessionId, e.getMessage());
            return null;
        }
    }

    private void restoreAgentHistory(Agent agent, String sessionId, String data) {
        try {
            var domain = JsonUtil.fromJson(AgentPersistence.AgentPersistenceDomain.class, data);
            if (domain.messages != null && !domain.messages.isEmpty()) {
                agent.restoreHistory(domain.messages);
                LOGGER.debug("restored {} messages from session data, sessionId={}", domain.messages.size(), sessionId);
            }
        } catch (Exception e) {
            LOGGER.debug("failed to restore agent history from session data, sessionId={}", sessionId, e);
        }
    }

    public void closeSession(String sessionId) {
        LOGGER.info("closeSession called, sessionId={}", sessionId);
        var chatSession = sessions.remove(sessionId);
        if (chatSession != null) {
            chatSession.signalSseClose();
            chatSession.interruptSseHandler();
            try {
                chatSession.session.close();
                LOGGER.info("closeSession completed, sessionId={}", sessionId);
            } catch (Exception e) {
                LOGGER.debug("failed to close session, id={}", sessionId, e);
            }
        }
    }

    public List<SessionPersistence.SessionInfo> listSessions() {
        if (sessionManager == null) {
            return List.of();
        }
        return sessionManager.listSessions();
    }

    public List<ChatSession> getAllSessions() {
        return new java.util.ArrayList<>(sessions.values());
    }

    public void closeAll() {
        LOGGER.info("closeAll called, sessions count={}", sessions.size());
        for (var chatSession : sessions.values()) {
            chatSession.signalSseClose();
            chatSession.interruptSseHandler();
            try {
                chatSession.session.close();
            } catch (Exception e) {
                LOGGER.debug("failed to close session", e);
            }
        }
        sessions.clear();
        LOGGER.info("closeAll completed");
    }

    public static class ChatSession {
        public final InProcessAgentSession session;
        private final List<AgentEventListener> listeners = new ArrayList<>();
        private final CountDownLatch sseLatch = new CountDownLatch(1);
        private volatile Thread sseHandlerThread;

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

        public void setSseHandlerThread(Thread thread) {
            this.sseHandlerThread = thread;
        }

        public boolean waitForSseClose(long timeout, TimeUnit unit) throws InterruptedException {
            return sseLatch.await(timeout, unit);
        }

        public void signalSseClose() {
            sseLatch.countDown();
        }

        public void interruptSseHandler() {
            Thread thread = this.sseHandlerThread;
            if (thread != null && thread.isAlive()) {
                thread.interrupt();
            }
        }
    }
}
