package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectAttribution;

import java.time.ZonedDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public interface ProjectAttributionRepository {
    Optional<ProjectSubjectAttribution> get(String id);

    List<ProjectSubjectAttribution> byTarget(String targetType, String targetId);

    Map<String, String> fileSubjects(String projectId);

    Optional<ProjectSubjectAttribution> fileAttribution(String projectId, String fileId);

    List<ProjectSubjectAttribution> filedFiles(String projectId, String subjectId,
                                                ZonedDateTime from, ZonedDateTime to, int offset, int limit);

    long filedFileCount(String projectId, String subjectId, ZonedDateTime from, ZonedDateTime to);

    Map<String, ProjectAttributionStore.SubjectFileStats> fileStatsBySubject(String projectId);

    boolean exists(String subjectId, String targetType, String targetId);

    boolean insertIfAbsent(ProjectSubjectAttribution attribution);

    void update(String id, Consumer<ProjectSubjectAttribution> mutator);

    void deleteById(String id);

    void deleteBySubject(String subjectId);

    void deleteByProjectAndTarget(String projectId, String targetType, String targetId);

    void clearAnalyzed(String projectId, String targetType, String targetId);

    void deleteByTargetTypeAndId(String targetType, String targetId);

    List<ProjectSubjectAttribution> listBySubject(String subjectId);

    long countBySubject(String subjectId);

    List<ProjectSubjectAttribution> listBySubjectAndType(String subjectId, String targetType);

    List<ProjectSubjectAttribution> listBySubjectsAndType(Collection<String> subjectIds, String targetType);

    List<ProjectSubjectAttribution> listByProjectAndType(String projectId, String targetType);

    List<ProjectSubjectAttribution> listUnanalyzed(String subjectId, int limit);

    long countUnanalyzed(String subjectId);

    List<ProjectSubjectAttribution> listByProjectAndTargets(String projectId, String targetType, Collection<String> targetIds);

    long countByProjectAndTarget(String projectId, String targetType, String targetId);
}
