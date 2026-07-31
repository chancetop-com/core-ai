package ai.core.api.server.run;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class TriggerRunRequest {
    @Property(name = "input")
    public String input;

    @Property(name = "attachments")
    public List<LLMCallRequest.Attachment> attachments;
}
