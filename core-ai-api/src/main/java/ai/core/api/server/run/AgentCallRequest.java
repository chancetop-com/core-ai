package ai.core.api.server.run;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

import java.util.List;

/**
 * @author Xander
 */
public class AgentCallRequest {
    @NotNull
    @Property(name = "input")
    public String input;

    @Property(name = "attachments")
    public List<LLMCallRequest.Attachment> attachments;
}
