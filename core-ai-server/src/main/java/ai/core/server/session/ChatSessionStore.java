package ai.core.server.session;

import ai.core.server.domain.AgentRunArtifact;
import ai.core.server.domain.ChatSession;
import ai.core.server.domain.ToolRef;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * Durable session metadata. Mutations are expressed as the operations the runtime actually needs
 * (message counters, artifact append, title edit) so the PostgreSQL implementation can lock a row
 * instead of replaying whole-document replaces.
 *
 * @author stephen
 */
public interface ChatSessionStore {
    Optional<ChatSession> get(String id);

    /** @return false when the id already exists */
    boolean insertIfAbsent(ChatSession session);

    /** migration/import path: replace the whole row when it already exists */
    void upsert(ChatSession session);

    List<ChatSession> find(ChatSessionQuery query);

    long count(ChatSessionQuery query);

    void recordComparison(String id, String comparisonId, String comparisonVariant, String comparisonPairId);

    void recordUserMessage(String id, String titleIfAbsent, ZonedDateTime now);

    void recordAgentMessage(String id, ZonedDateTime now);

    void addLoadedTools(String id, List<ToolRef> refs);

    void addLoadedSkillIds(String id, List<String> skillIds);

    void addLoadedSubAgentIds(String id, List<String> agentIds);

    void removeLoadedSkillIds(String id, List<String> skillIds);

    void appendArtifact(String id, AgentRunArtifact artifact);

    void updateTitle(String id, String title);

    void updateNotifyOnComplete(String id, boolean enabled);

    boolean softDelete(String id, ZonedDateTime at);
}
