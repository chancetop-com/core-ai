package ai.core.a2a;

import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/**
 * Process-local remote agent context store.
 *
 * @author xander
 */
public class InMemoryRemoteAgentContextStore implements RemoteAgentContextStore {
    private final ConcurrentMap<String, RemoteAgentContext> contexts = new ConcurrentHashMap<>();

    @Override
    public Optional<RemoteAgentContext> get(String localSessionId, String remoteAgentId) {
        return Optional.ofNullable(contexts.get(key(localSessionId, remoteAgentId)));
    }

    @Override
    public void save(RemoteAgentContext context) {
        if (context == null || context.localSessionId == null || context.remoteAgentId == null) return;
        contexts.put(key(context.localSessionId, context.remoteAgentId), context);
    }

    @Override
    public void delete(String localSessionId, String remoteAgentId) {
        contexts.remove(key(localSessionId, remoteAgentId));
    }

    private String key(String localSessionId, String remoteAgentId) {
        return localSessionId + ":" + remoteAgentId;
    }
}
