package ai.core.server.run;

import ai.core.server.domain.AgentRun;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentRunStore {
    Optional<AgentRun> get(String id);

    void insert(AgentRun run);

    void replace(AgentRun run);

    List<AgentRun> find(AgentRunQuery query);

    long count(AgentRunQuery query);

    Optional<AgentRun> findOne(AgentRunQuery query);

    boolean claimTraceId(String id, String traceId);

    boolean failIfUnfinished(String id, String error, ZonedDateTime completedAt);

    boolean reconcileIfUnfinished(String id, boolean completed, String output, String error, ZonedDateTime completedAt);
}
