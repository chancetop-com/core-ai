package ai.core.server.agent;

import ai.core.server.domain.AgentDefinition;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface AgentDefinitionStore {
    Optional<AgentDefinition> get(String id);

    List<AgentDefinition> find(AgentDefinitionQuery query);

    default Optional<AgentDefinition> findOne(AgentDefinitionQuery query) {
        query.limit = 1;
        return find(query).stream().findFirst();
    }

    long count(AgentDefinitionQuery query);

    void insert(AgentDefinition definition);

    void replace(AgentDefinition definition);

    /**
     * Single-document compare-and-set on {@code definitionRevision}. Returns false when the document no
     * longer carries the expected revision (or was deleted), which callers must surface as a conflict
     * instead of overwriting.
     */
    boolean replaceIfRevision(AgentDefinition definition, int expectedRevision);

    void upsert(AgentDefinition definition);

    void delete(String id);

    boolean insertIfAbsent(AgentDefinition definition);
}
