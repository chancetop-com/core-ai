package ai.core.server.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import redis.clients.jedis.JedisPool;
import redis.clients.jedis.StreamEntryID;
import redis.clients.jedis.params.XReadGroupParams;
import redis.clients.jedis.params.XReadParams;

import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
@SuppressWarnings("deprecation")
public class CommandConsumer {
    private static final Logger LOGGER = LoggerFactory.getLogger(CommandConsumer.class);
    private static final int BLOCK_MS = 5000;
    private static final int MAX_COUNT = 100;

    private final JedisPool jedisPool;
    private final InProcessCommandHandler commandHandler;
    private final SessionOwnershipRegistry ownershipRegistry;
    private final String hostname;

    private volatile boolean running = true;
    private Thread podStreamThread;
    private Thread unownedStreamThread;

    public CommandConsumer(JedisPool jedisPool,
                           InProcessCommandHandler commandHandler,
                           SessionOwnershipRegistry ownershipRegistry) {
        this.jedisPool = jedisPool;
        this.commandHandler = commandHandler;
        this.ownershipRegistry = ownershipRegistry;
        this.hostname = ownershipRegistry.getHostname();
    }

    public void start() {
        initUnownedConsumerGroup();
        podStreamThread = Thread.ofVirtual()
                .name("cmd-consumer-pod-" + hostname)
                .start(this::podStreamLoop);
        unownedStreamThread = Thread.ofVirtual()
                .name("cmd-consumer-unowned")
                .start(this::unownedStreamLoop);
        LOGGER.info("CommandConsumer started, hostname={}", hostname);
    }

    public void stop() {
        running = false;
        if (podStreamThread != null) podStreamThread.interrupt();
        if (unownedStreamThread != null) unownedStreamThread.interrupt();
    }

    public void drainPodStream(List<SessionCommand> buffer) {
        try (var jedis = jedisPool.getResource()) {
            var streamKey = SessionCommand.podStreamKey(hostname);
            while (true) {
                var entries = jedis.xread(XReadParams.xReadParams()
                                .block(100).count(MAX_COUNT),
                        Map.of(streamKey, new StreamEntryID(0, 0)));
                if (entries == null || entries.isEmpty()) break;
                for (var stream : entries) {
                    for (var entry : stream.getValue()) {
                        var command = SessionCommand.fromMap(entry.getFields());
                        buffer.add(command);
                        jedis.xdel(streamKey, entry.getID());
                        LOGGER.debug("drained command, type={}, sessionId={}", command.type(), command.sessionId());
                    }
                }
            }
        }
        LOGGER.info("drained {} commands from per-Pod stream", buffer.size());
    }

    private void podStreamLoop() {
        var streamKey = SessionCommand.podStreamKey(hostname);
        LOGGER.info("consuming from pod stream: {}", streamKey);
        while (running) {
            try (var jedis = jedisPool.getResource()) {
                var entries = jedis.xread(XReadParams.xReadParams()
                                .block(BLOCK_MS).count(MAX_COUNT),
                        Map.of(streamKey, new StreamEntryID(0, 0)));
                if (entries == null || entries.isEmpty()) continue;
                for (var stream : entries) {
                    for (var entry : stream.getValue()) {
                        if (!running) break;
                        var command = SessionCommand.fromMap(entry.getFields());
                        try {
                            commandHandler.handle(command);
                        } catch (Throwable t) {
                            // never let an error trap us on this entry forever
                            LOGGER.error("pod stream handle threw, sessionId={}, type={}", command.sessionId(), command.type(), t);
                        } finally {
                            // always delete: per-Pod stream is exclusive, no ACK needed,
                            // and a poison entry must not block subsequent commands
                            safeXdel(jedis, streamKey, entry.getID());
                        }
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    LOGGER.warn("pod stream consumer error, reconnecting in 3s...", t);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void unownedStreamLoop() {
        LOGGER.info("consuming from unowned stream: {}", SessionCommand.UNOWNED_STREAM);
        while (running) {
            try (var jedis = jedisPool.getResource()) {
                var entries = jedis.xreadGroup(SessionCommand.UNOWNED_CONSUMER_GROUP, hostname,
                        XReadGroupParams.xReadGroupParams().block(BLOCK_MS).count(MAX_COUNT),
                        Map.of(SessionCommand.UNOWNED_STREAM, StreamEntryID.UNRECEIVED_ENTRY));
                if (entries == null || entries.isEmpty()) continue;
                for (var stream : entries) {
                    for (var entry : stream.getValue()) {
                        if (!running) break;
                        var command = SessionCommand.fromMap(entry.getFields());
                        var sessionId = command.sessionId();
                        try {
                            if (ownershipRegistry.claim(sessionId)) {
                                LOGGER.info("claimed session from unowned stream, sessionId={}", sessionId);
                                commandHandler.handle(command);
                            } else {
                                // Another Pod already claimed this session — that Pod will
                                // also have received the command via its per-Pod stream
                                LOGGER.debug("session already claimed by another pod, skipping, sessionId={}", sessionId);
                            }
                        } catch (Throwable t) {
                            LOGGER.error("unowned stream handle threw, sessionId={}, type={}", sessionId, command.type(), t);
                        } finally {
                            safeXack(jedis, SessionCommand.UNOWNED_STREAM, SessionCommand.UNOWNED_CONSUMER_GROUP, entry.getID());
                        }
                    }
                }
            } catch (Throwable t) {
                if (running) {
                    LOGGER.warn("unowned stream consumer error, reconnecting in 3s...", t);
                    sleepBeforeReconnect();
                }
            }
        }
    }

    private void safeXdel(redis.clients.jedis.Jedis jedis, String streamKey, StreamEntryID id) {
        try {
            jedis.xdel(streamKey, id);
        } catch (Throwable t) {
            LOGGER.warn("xdel failed, streamKey={}, id={}", streamKey, id, t);
        }
    }

    private void safeXack(redis.clients.jedis.Jedis jedis, String streamKey, String group, StreamEntryID id) {
        try {
            jedis.xack(streamKey, group, id);
        } catch (Throwable t) {
            LOGGER.warn("xack failed, streamKey={}, id={}", streamKey, id, t);
        }
    }

    private void initUnownedConsumerGroup() {
        try (var jedis = jedisPool.getResource()) {
            jedis.xgroupCreate(SessionCommand.UNOWNED_STREAM, SessionCommand.UNOWNED_CONSUMER_GROUP,
                    StreamEntryID.LAST_ENTRY, true);
            LOGGER.info("consumer group created: {}", SessionCommand.UNOWNED_CONSUMER_GROUP);
        } catch (Exception e) {
            LOGGER.debug("consumer group already exists: {}", SessionCommand.UNOWNED_CONSUMER_GROUP);
        }
    }

    private void sleepBeforeReconnect() {
        try {
            Thread.sleep(3000);
        } catch (InterruptedException ie) {
            Thread.currentThread().interrupt();
        }
    }
}
