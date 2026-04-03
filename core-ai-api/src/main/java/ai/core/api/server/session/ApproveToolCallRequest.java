package ai.core.api.server.session;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author stephen
 */
public class ApproveToolCallRequest {
    @NotNull
    @Property(name = "call_id")
    public String callId;

    @NotNull
    @Property(name = "decision")
    public ApprovalDecision decision;
}
