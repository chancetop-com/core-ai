package ai.core.session;

import ai.core.agent.AgentPersistence;

import java.io.IOException;

/**
 * Result of reading one session snapshot. The snapshot is parsed exactly once; callers that need to
 * restore an agent use the domain carried by {@link Ok}.
 *
 * @author stephen
 */
public sealed interface SessionLoad {
    record Ok(AgentPersistence.AgentPersistenceDomain domain) implements SessionLoad {
    }

    record Missing() implements SessionLoad {
    }

    record Corrupt(String reason) implements SessionLoad {
    }

    record NewerVersion(int version) implements SessionLoad {
    }

    record IoError(IOException cause) implements SessionLoad {
    }
}
