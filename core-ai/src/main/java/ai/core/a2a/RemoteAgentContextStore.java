package ai.core.a2a;

import java.util.Optional;

/**
 * Stores opaque remote agent task/context identifiers outside the LLM-visible tool schema.
 *
 * @author xander
 */
public interface RemoteAgentContextStore {
    Optional<RemoteAgentContext> get(String localSessionId, String remoteAgentId);

    void save(RemoteAgentContext context);

    void delete(String localSessionId, String remoteAgentId);
}
