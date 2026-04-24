package ai.core.server.messaging;

import ai.core.api.server.session.ApprovalDecision;
import ai.core.server.session.AgentSessionManager;
import ai.core.server.session.ChatMessageService;
import ai.core.utils.JsonUtil;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.Jedis;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.resps.StreamEntry;

import java.util.Map;

/**
 * @author stephen
 */
public class InProcessCommandHandler {
    private static final Logger LOGGER = LoggerFactory.getLogger(InProcessCommandHandler.class);
    private static final String CONSUMER_NAME = "combined-worker";
    private static final int BLOCK_TIMEOUT_MS = 5000;
    private static final int MAX_COUNT = 100;

    private final JedisPool jedisPool;
    private final AgentSessionManager sessionManager;
    private final ChatMessageService chatMessageService;
    private volatile boolean running = true;
    private Thread handlerThread;

    public InProcessCommandHandler(JedisPool jedisPool,
                                   AgentSessionManager sessionManager,
                                   ChatMessageService chatMessageService) {
        this.jedisPool = jedisPool;
        this.sessionManager = sessionManager;
        this.chatMessageService = chatMessageService;
    }

    public void start() {
        // Initialize consumer group
        initConsumerGroup();

        handlerThread = Thread.ofVirtual()
                .name("command-handler")
                .start(this::handlerLoop);
        LOGGER.info("InProcessCommandHandler started, consumer={}", CONSUMER_NAME);
    }

    @SuppressWarnings("deprecation")
    private void initConsumerGroup() {
        try (var jedis = jedisPool.getResource()) {
            jedis.xgroupCreate(SessionCommand.streamKey(), SessionCommand.consumerGroup(),
                    StreamEntryID.LAST_ENTRY, true);
            LOGGER.info("consumer group created: {}", SessionCommand.consumerGroup());
        } catch (Exception e) {
            // Group already exists - this is expected
            LOGGER.debug("consumer group already exists: {}", SessionCommand.consumerGroup());
        }
    }

    public void stop() {
        running = false;
        if (handlerThread != null) {
            handlerThread.interrupt();
        }
    }

    @SuppressWarnings("deprecation")
    private void handlerLoop() {
        LOGGER.info("command handler loop started");
        while (running) {
            try (Jedis jedis = jedisPool.getResource()) {
                var entries = jedis.xreadGroup(
                        SessionCommand.consumerGroup(),
                        CONSUMER_NAME,
                        XReadGroupParams.xReadGroupParams()
                                .block(BLOCK_TIMEOUT_MS)
                                .count(MAX_COUNT),
                        Map.of(SessionCommand.streamKey(), StreamEntryID.UNRECEIVED_ENTRY)
                );

                if (entries == null || entries.isEmpty()) continue;

                LOGGER.info("xreadGroup returned {} streams", entries.size());
                for (var streamEntry : entries) {
                    var streamKey = streamEntry.getKey();
                    var messages = streamEntry.getValue();
                    LOGGER.info("processing stream={}, messageCount={}", streamKey, messages.size());
                    for (var message : messages) {
                        processMessage(jedis, streamKey, message);
                    }
                }
            } catch (Exception e) {
                if (running) {
                    LOGGER.warn("command handler error, reconnecting in 3s...", e);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }

    private void processMessage(Jedis jedis, String streamKey, StreamEntry message) {
        var messageId = message.getID();
        LOGGER.info("processing message: messageId={}", messageId);
        try {
            var command = SessionCommand.fromMap(message.getFields());
            LOGGER.info("processing command: type={}, sessionId={}, messageId={}",
                    command.type(), command.sessionId(), messageId);

            switch (command.type()) {
                case SEND_MESSAGE -> handleSendMessage(command);
                case APPROVE_TOOL -> handleApproveTool(command);
                case CANCEL_TURN -> handleCancelTurn(command);
                case CLOSE_SESSION -> handleCloseSession(command);
                default -> LOGGER.warn("unknown command type: {}", command.type());
            }

            // Acknowledge the message after successful processing
            jedis.xack(streamKey, SessionCommand.consumerGroup(), messageId);
        } catch (Exception e) {
            LOGGER.warn("failed to process command, messageId={}", messageId, e);
            // Ack to avoid blocking the stream, even on failure
            ackSafely(jedis, streamKey, messageId);
        }
    }

    private void ackSafely(Jedis jedis, String streamKey, StreamEntryID messageId) {
        try {
            jedis.xack(streamKey, SessionCommand.consumerGroup(), messageId);
        } catch (Exception ackEx) {
            LOGGER.warn("failed to ack message, messageId={}", messageId, ackEx);
        }
    }

    @SuppressWarnings("unchecked")
    private void handleSendMessage(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var message = (String) payload.get("message");
        var variables = (Map<String, Object>) payload.get("variables");
        if (variables == null || variables.isEmpty()) variables = null;

        LOGGER.info("handleSendMessage: looking up session sessionId={}", command.sessionId());
        var session = sessionManager.getSession(command.sessionId());
        LOGGER.info("handleSendMessage: session found, writing user message");
        chatMessageService.writeUserMessage(command.sessionId(), message);
        LOGGER.info("handleSendMessage: sending message to agent");
        session.sendMessage(message, variables);
        LOGGER.info("handleSendMessage: message sent to agent");
    }

    private void handleApproveTool(SessionCommand command) {
        var payload = JsonUtil.fromJson(Map.class, command.payload());
        var callId = (String) payload.get("callId");
        var decision = ApprovalDecision.valueOf((String) payload.get("decision"));

        var session = sessionManager.getSession(command.sessionId());
        session.approveToolCall(callId, decision);
    }

    private void handleCancelTurn(SessionCommand command) {
        var session = sessionManager.getSession(command.sessionId());
        session.cancelTurn();
    }

    private void handleCloseSession(SessionCommand command) {
        sessionManager.closeSession(command.sessionId());
    }
}
