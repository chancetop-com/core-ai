package ai.core.server.workflow;

import ai.core.server.domain.WorkflowPublishedVersion;
import ai.core.server.workflow.engine.WorkflowGraph;
import core.framework.inject.Inject;
import core.framework.mongo.MongoCollection;

/**
 * Loads the engine graph for a pinned published version: fetch the immutable snapshot, verify its sha256
 * (reject corruption), and parse it. The runner depends on the {@link WorkflowGraphLoader} seam; this is the
 * production binding.
 *
 * @author Xander
 */
public class MongoWorkflowGraphLoader implements WorkflowGraphLoader {
    @Inject
    MongoCollection<WorkflowPublishedVersion> versionCollection;

    @Override
    public WorkflowGraph load(String versionId) {
        WorkflowPublishedVersion version = versionCollection.get(versionId)
            .orElseThrow(() -> new IllegalStateException("published workflow version not found: " + versionId));
        if (!WorkflowSha.hex(version.graph).equals(version.sha256)) {
            throw new IllegalStateException("graph sha256 mismatch for version " + versionId);
        }
        return WorkflowGraphParser.parse(version.graph);
    }
}
