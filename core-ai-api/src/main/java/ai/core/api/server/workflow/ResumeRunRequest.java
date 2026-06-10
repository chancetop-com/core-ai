package ai.core.api.server.workflow;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * Resume a paused workflow run by settling the HUMAN_INPUT node it is waiting on.
 *
 * @author Xander
 */
public class ResumeRunRequest {
    @NotNull
    @Property(name = "node_id")
    public String nodeId;

    // approval mode: true = take the approve edge, false = the reject edge. Ignored in input mode.
    @Property(name = "approve")
    public Boolean approve;

    // input mode: the human-provided value (a JSON object string) that becomes the node output. Ignored in approval mode.
    @Property(name = "input")
    public String input;
}
