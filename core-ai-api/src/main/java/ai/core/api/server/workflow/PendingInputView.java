package ai.core.api.server.workflow;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Self-describing wait of a PAUSED run: which HUMAN_INPUT node is waiting, what it asks, and (input mode)
 * the form schema the answer must satisfy — everything an API caller needs to build the resume request.
 *
 * @author Xander
 */
public class PendingInputView {
    @Property(name = "node_id")
    public String nodeId;

    // "approval" (resume with 'approve') | "input" (resume with 'input' matching 'fields')
    @Property(name = "mode")
    public String mode;

    @Property(name = "prompt")
    public String prompt;

    // input mode only: the form schema snapshot from the run's pinned graph version
    @Property(name = "fields")
    public List<PendingInputFieldView> fields;
}
