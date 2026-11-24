package ai.core.server.agenthealth;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Notification ledger for broken references. The derived health badge must not depend on this store;
 * it only answers "has this episode already been notified?" and keeps the audit trail.
 *
 * @author core-ai
 */
public interface AgentReferenceIssueStore {
    Optional<AgentReferenceIssue> findActive(String agentId, String refType, String refId);

    AgentReferenceIssue upsertActive(AgentReferenceIssue issue);

    List<AgentReferenceIssue> listUnnotified(String agentId);

    List<AgentReferenceIssue> listActiveByAgentIds(Collection<String> agentIds);

    List<AgentReferenceIssue> listActive(int limit, String afterId);

    void markNotified(Collection<String> issueIds, ZonedDateTime at);

    void resolve(Collection<String> issueIds, String resolution, ZonedDateTime at);

    void resolveByAgent(String agentId, String resolution, ZonedDateTime at);

    AgentHealthState state();

    void saveState(AgentHealthState state);
}
