package ai.core.server.agenthealth;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * Degraded ledger for non-PG store modes: the derived badge still works, notifications and audit do not.
 *
 * @author core-ai
 */
public class NoopAgentReferenceIssueStore implements AgentReferenceIssueStore {
    @Override
    public Optional<AgentReferenceIssue> findActive(String agentId, String refType, String refId) {
        return Optional.empty();
    }

    @Override
    public AgentReferenceIssue upsertActive(AgentReferenceIssue issue) {
        return issue;
    }

    @Override
    public List<AgentReferenceIssue> listUnnotified(String agentId) {
        return List.of();
    }

    @Override
    public List<AgentReferenceIssue> listActiveByAgentIds(Collection<String> agentIds) {
        return List.of();
    }

    @Override
    public List<AgentReferenceIssue> listActive(int limit, String afterId) {
        return List.of();
    }

    @Override
    public void markNotified(Collection<String> issueIds, ZonedDateTime at) {
    }

    @Override
    public void resolve(Collection<String> issueIds, String resolution, ZonedDateTime at) {
    }

    @Override
    public void resolveByAgent(String agentId, String resolution, ZonedDateTime at) {
    }

    @Override
    public AgentHealthState state() {
        var state = new AgentHealthState();
        state.id = "reconciler";
        state.backfillDone = Boolean.TRUE;
        return state;
    }

    @Override
    public void saveState(AgentHealthState state) {
    }
}
