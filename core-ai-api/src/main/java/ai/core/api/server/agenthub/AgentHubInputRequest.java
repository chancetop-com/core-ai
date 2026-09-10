package ai.core.api.server.agenthub;

import core.framework.api.json.Property;

/**
 * The approval or additional input an {@code input_required} run is waiting for. Mirrors the
 * A2A tool-approval metadata ({@code call_id}/{@code tool}/{@code arguments}) plus the human
 * readable message.
 *
 * @author stephen
 */
public class AgentHubInputRequest {
    @Property(name = "call_id")
    public String callId;

    @Property(name = "tool")
    public String tool;

    @Property(name = "arguments")
    public String arguments;

    @Property(name = "message")
    public String message;
}
