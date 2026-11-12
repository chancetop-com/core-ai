package ai.core.server.workflow;

import ai.core.server.domain.WorkflowDefinition;

import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkflowDefinitionStore {
    Optional<WorkflowDefinition> get(String id);

    void insert(WorkflowDefinition definition);

    void replace(WorkflowDefinition definition);

    void delete(String id);

    List<WorkflowDefinition> listActive(String userId, Boolean myWorkflows, String keyword, int offset, int limit);

    long countActive(String userId, Boolean myWorkflows, String keyword);

    List<WorkflowDefinition> listArchived(String userId, String keyword, int offset, int limit);

    long countArchived(String userId, String keyword);

    List<WorkflowDefinition> explore(String userId, String keyword, int offset, int limit);

    long exploreCount(String userId, String keyword);

    List<WorkflowDefinition> findByIds(List<String> ids);

    List<WorkflowDefinition> listOwned(String userId);

    List<WorkflowDefinition> listPublicActive();
}
