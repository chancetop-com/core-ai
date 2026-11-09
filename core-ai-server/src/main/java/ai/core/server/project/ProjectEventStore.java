package ai.core.server.project;

import ai.core.server.domain.ProjectSubjectEvent;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface ProjectEventStore {
    Optional<ZonedDateTime> latestAt(String subjectId);

    long countByProjectAndSubject(String projectId, String subjectId);

    List<ProjectSubjectEvent> listByProjectAndSubject(String projectId, String subjectId, int offset, int limit);

    List<ProjectSubjectEvent> listBySubjectAndType(String subjectId, String type, int limit);

    List<ProjectSubjectEvent> listByProject(String projectId, String subjectId, String type,
                                             ZonedDateTime from, ZonedDateTime to, int limit);

    boolean exists(String projectId, String subjectId, String type, String key, String value, ZonedDateTime at);

    void insert(ProjectSubjectEvent event);

    void deleteBySubject(String subjectId);
}
