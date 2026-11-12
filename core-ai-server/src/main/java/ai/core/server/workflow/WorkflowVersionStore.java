package ai.core.server.workflow;

import ai.core.server.domain.WorkflowPublishedVersion;

import java.time.ZonedDateTime;
import java.util.List;
import java.util.Optional;

/**
 * @author stephen
 */
public interface WorkflowVersionStore {
    Optional<WorkflowPublishedVersion> get(String id);

    void insert(WorkflowPublishedVersion version);

    List<WorkflowPublishedVersion> listNonPreview(String workflowId);

    List<WorkflowPublishedVersion> listAll(String workflowId);

    int deletePreviewOlderThan(ZonedDateTime cutoff);
}
