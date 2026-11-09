package ai.core.server.project;

import ai.core.server.domain.ProjectStatsEntity;

import java.util.Optional;

/**
 * @author stephen
 */
public interface ProjectStatsStore {
    Optional<ProjectStatsEntity> get(String id);

    void replace(ProjectStatsEntity entity);
}
