package ai.core.server.project;

import ai.core.server.domain.ProjectSubject;

import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public interface ProjectSubjectStore {
    Optional<ProjectSubject> get(String id);

    List<ProjectSubject> listByProject(String projectId);

    List<ProjectSubject> listStarted(String projectId);

    long countStarted(String projectId);

    List<ProjectSubject> listWithReportRunId(int limit);

    void insert(ProjectSubject subject);

    boolean insertIfAbsent(ProjectSubject subject);

    Optional<ProjectSubject> findAutoByNameKey(String projectId, String nameKey);

    void update(String id, Consumer<ProjectSubject> mutator);

    void delete(String id);
}
