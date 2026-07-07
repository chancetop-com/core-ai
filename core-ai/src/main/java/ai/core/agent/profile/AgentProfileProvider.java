package ai.core.agent.profile;

import java.util.List;

/**
 * Abstraction for agent profile sources. Implementations provide agent profiles
 * from different backends: filesystem, database, built-in registry, etc.
 *
 * @author lim chen
 */
public interface AgentProfileProvider {

    List<AgentProfile> provide();

    int priority();
}
