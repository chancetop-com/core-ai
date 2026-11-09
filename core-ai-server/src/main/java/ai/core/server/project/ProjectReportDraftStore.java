package ai.core.server.project;

import ai.core.server.domain.ProjectReportDraft;

import java.util.Optional;
import java.util.function.Consumer;

/**
 * @author stephen
 */
public interface ProjectReportDraftStore {
    Optional<ProjectReportDraft> get(String id);

    void insert(ProjectReportDraft draft);

    void update(String id, Consumer<ProjectReportDraft> mutator);

    void delete(String id);

    void deleteBySubject(String subjectId);
}
