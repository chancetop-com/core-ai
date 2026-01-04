package ai.core.memory.history;

import ai.core.agent.ExecutionContext;
import ai.core.agent.lifecycle.AbstractLifecycle;
import ai.core.llm.domain.CompletionRequest;
import ai.core.llm.domain.CompletionResponse;
import ai.core.llm.domain.Message;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.atomic.AtomicReference;

/**
 * @author xander
 */
public class ChatHistoryLifecycle extends AbstractLifecycle {

    private static final Logger LOGGER = LoggerFactory.getLogger(ChatHistoryLifecycle.class);

    private final ChatHistoryStore historyStore;
    private final String agentId;

    private ChatSession currentSession;
    private List<Message> pendingMessages;

    public ChatHistoryLifecycle(ChatHistoryStore historyStore) {
        this(historyStore, null);
    }

    public ChatHistoryLifecycle(ChatHistoryStore historyStore, String agentId) {
        this.historyStore = historyStore;
        this.agentId = agentId;
    }

    @Override
    public void beforeAgentRun(AtomicReference<String> query, ExecutionContext executionContext) {
        initializeSession(executionContext);
        LOGGER.debug("ChatHistoryLifecycle initialized, sessionId={}",
            currentSession != null ? currentSession.getId() : "none");
    }

    @Override
    public void beforeModel(CompletionRequest request, ExecutionContext executionContext) {
        // Track the last message in the request (typically the new user message)
        if (pendingMessages != null && request != null && request.messages != null && !request.messages.isEmpty()) {
            var lastMsg = request.messages.getLast();
            // Only add if it's not already tracked (by reference)
            if (!containsByReference(pendingMessages, lastMsg)) {
                pendingMessages.add(lastMsg);
            }
        }
    }

    @Override
    public void afterModel(CompletionRequest request, CompletionResponse response, ExecutionContext executionContext) {
        // Track assistant response messages
        if (pendingMessages != null && response != null && response.choices != null) {
            for (var choice : response.choices) {
                if (choice.message != null && !containsByReference(pendingMessages, choice.message)) {
                    pendingMessages.add(choice.message);
                }
            }
        }
    }

    @SuppressWarnings("PMD.CompareObjectsWithEquals")
    private boolean containsByReference(List<Message> list, Message msg) {
        // Intentionally using == for reference equality check
        for (Message m : list) {
            if (m == msg) {
                return true;
            }
        }
        return false;
    }

    @Override
    public void afterAgentRun(String query, AtomicReference<String> result, ExecutionContext executionContext) {
        try {
            persistSession();
        } finally {
            currentSession = null;
            pendingMessages = null;
        }
    }

    public ChatSession getCurrentSession() {
        return currentSession;
    }

    public ChatHistoryStore getHistoryStore() {
        return historyStore;
    }

    private void initializeSession(ExecutionContext context) {
        if (context == null) {
            LOGGER.debug("No execution context, chat history will not be saved");
            return;
        }

        String userId = context.getUserId();
        String sessionId = context.getSessionId();

        if (userId == null || userId.isBlank()) {
            LOGGER.debug("No userId in context, chat history will not be saved");
            return;
        }

        pendingMessages = new ArrayList<>();

        // Try to load existing session or create new one
        if (sessionId != null && !sessionId.isBlank()) {
            var existing = historyStore.findById(sessionId);
            if (existing.isPresent()) {
                currentSession = existing.get();
                LOGGER.debug("Loaded existing session: {}", sessionId);
                return;
            }
        }

        // Create new session
        currentSession = ChatSession.builder()
            .id(sessionId)
            .userId(userId)
            .agentId(agentId)
            .build();

        LOGGER.debug("Created new session: {}", currentSession.getId());
    }

    private void persistSession() {
        if (currentSession == null || pendingMessages == null) {
            return;
        }

        // Get messages tracked during execution
        List<Message> messagesToSave = getMessagesToSave();
        if (messagesToSave.isEmpty()) {
            LOGGER.debug("No new messages to persist");
            return;
        }

        try {
            // Update title if not set
            if (currentSession.getTitle() == null || currentSession.getTitle().equals("New Chat")) {
                currentSession.setTitle(generateTitle(messagesToSave));
            }

            currentSession.setMessages(messagesToSave);
            historyStore.save(currentSession);

            LOGGER.info("Persisted {} messages to session {}", messagesToSave.size(), currentSession.getId());
        } catch (Exception e) {
            LOGGER.error("Failed to persist chat history for session {}", currentSession.getId(), e);
        }
    }

    private List<Message> getMessagesToSave() {
        // If we have pending messages tracked during execution, use them
        if (!pendingMessages.isEmpty()) {
            return new ArrayList<>(pendingMessages);
        }

        // Otherwise return empty (fallback when afterModel wasn't called)
        return List.of();
    }

    private String generateTitle(List<Message> messages) {
        if (messages == null || messages.isEmpty()) {
            return "New Chat";
        }
        for (Message msg : messages) {
            if (msg.role != null && "USER".equals(msg.role.name()) && msg.content != null) {
                String content = msg.content.trim();
                if (content.length() > 50) {
                    return content.substring(0, 47) + "...";
                }
                return content;
            }
        }
        return "New Chat";
    }

    /**
     * Manually add a message to pending (for cases where tracking via lifecycle doesn't work).
     */
    public void trackMessage(Message message) {
        if (pendingMessages != null && message != null) {
            pendingMessages.add(message);
        }
    }

    /**
     * Manually add messages to pending.
     */
    public void trackMessages(List<Message> messages) {
        if (pendingMessages != null && messages != null) {
            pendingMessages.addAll(messages);
        }
    }
}
