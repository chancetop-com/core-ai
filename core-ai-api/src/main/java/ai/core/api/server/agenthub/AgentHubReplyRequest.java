package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

/**
 * Reply to an {@code input_required} run: a tool approval decision ({@code approve}/{@code deny})
 * and/or free text that continues the conversation.
 *
 * @author stephen
 */
public class AgentHubReplyRequest {
    @Property(name = "decision")
    public String decision;

    @Property(name = "message")
    public String message;
}
