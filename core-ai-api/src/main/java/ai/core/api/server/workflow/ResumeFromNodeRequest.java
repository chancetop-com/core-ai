package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Resume a finished (or failed) workflow run from an intermediate node: the node and its forward cone re-run,
 * everything upstream is reused from the source run.
 *
 * @author Xander
 */
public class ResumeFromNodeRequest {
    @NotNull
    @Property(name = "node_id")
    public String nodeId;
}
