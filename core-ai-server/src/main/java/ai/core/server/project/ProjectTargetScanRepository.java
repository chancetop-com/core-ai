package ai.core.server.project;

import ai.core.server.domain.ProjectTargetScan;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ProjectTargetScanRepository {
    List<ProjectTargetScan> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds);

    List<ProjectTargetScan> listByProject(String projectId);

    Optional<ProjectTargetScan> find(String projectId, String targetType, String targetId);

    void upsert(ProjectTargetScan scan);

    void deleteById(String id);

    void deleteByProjectAndTarget(String projectId, String targetType, String targetId);

    Optional<ZonedDateTime> oldestMaterialAt(String projectId);
}
