package ai.core.server.channel;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * @author stephen
 */
public class ChannelRegistry {
    private static final Logger LOGGER = LoggerFactory.getLogger(ChannelRegistry.class);

    // Registered adapters by channel type
    private final ConcurrentMap<String, ChannelInboundAdapter> inboundAdapters = new ConcurrentHashMap<>();
    private final ConcurrentMap<String, ChannelOutboundAdapter> outboundAdapters = new ConcurrentHashMap<>();

    // Active channel bridges per session — maps sessionId to its ChannelEventBridge
    private final ConcurrentMap<String, ChannelEventBridge> sessionBridges = new ConcurrentHashMap<>();

    /**
     * Register a channel adapter pair during channel infrastructure initialization.
     */
    public void register(ChannelInboundAdapter inbound, ChannelOutboundAdapter outbound) {
        var type = inbound.type();
        if (!type.equals(outbound.type())) {
            throw new IllegalArgumentException(
                    "inbound and outbound type mismatch: " + type + " vs " + outbound.type());
        }
        inboundAdapters.put(type, inbound);
        outboundAdapters.put(type, outbound);
        LOGGER.info("registered channel adapter: type={}", type);
    }

    /** Resolve the inbound adapter for a channel type. */
    public ChannelInboundAdapter inbound(String type) {
        var adapter = inboundAdapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("no inbound adapter registered for channel type: " + type);
        }
        return adapter;
    }

    /** Resolve the outbound adapter for a channel type. */
    public ChannelOutboundAdapter outbound(String type) {
        var adapter = outboundAdapters.get(type);
        if (adapter == null) {
            throw new IllegalArgumentException("no outbound adapter registered for channel type: " + type);
        }
        return adapter;
    }

    /** Returns true if the channel type has registered adapters. */
    public boolean isRegistered(String type) {
        return inboundAdapters.containsKey(type);
    }

    /**
     * Attach a ChannelEventBridge to an existing session.
     * Called lazily by ChannelDispatcher on first dispatch to a session.
     * Idempotent — if a bridge already exists for this session, returns without action.
     */
    public void ensureChannelBridge(String sessionId, ai.core.session.InProcessAgentSession session,
                                     ChannelConfigView channel, InboundEvent event) {
        if (sessionBridges.containsKey(sessionId)) return;

        var outbound = outbound(channel.channelType);
        var bridge = new ChannelEventBridge(outbound, event.channelUserId,
                event.conversationId, event.threadId, channel.config);
        session.onEvent(bridge);
        sessionBridges.put(sessionId, bridge);
        LOGGER.info("attached channel event bridge, sessionId={}, channelType={}", sessionId, channel.channelType);
    }

    /** Remove the channel bridge when a session closes. */
    public void removeSessionBridge(String sessionId) {
        sessionBridges.remove(sessionId);
    }

    /** Returns all registered channel types. */
    public List<String> registeredTypes() {
        return new ArrayList<>(inboundAdapters.keySet());
    }
}
