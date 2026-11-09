package ai.core.server.project;

import ai.core.server.domain.Project;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Project storage. Writes are load/mutate/diff/patch rather than whole-document replace, because the
 * analysis jobs update cursors and claims on the same project concurrently with user edits.
 *
 * @author stephen
 */
public interface ProjectStore {
    Optional<Project> get(String id);

    void insert(Project project);

    boolean update(String id, Consumer<Project> mutator);

    boolean updateIf(String id, Predicate<Project> guard, Consumer<Project> mutator);

    List<Project> list(String userId, Boolean archivedOnly, int offset, int limit);

    long count(String userId, Boolean archivedOnly);

    List<Project> listForAttribution(ZonedDateTime cutoff, int limit);

    List<Project> listForAnalysis(ZonedDateTime cutoff, int limit);

    List<Project> listDirtyStats(int limit);
}
