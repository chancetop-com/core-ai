package ai.core.api.server.workflow;

import core.framework.api.json.Property;

/**
 * The frozen graph JSON the run actually executed (the run's pinned published/preview version), so run history
 * renders its trace against the snapshot that ran — not the live, possibly-edited draft.
 *
 * @author Xander
 */
public class WorkflowRunGraphResponse {
    @Property(name = "graph")
    public String graph;
}
