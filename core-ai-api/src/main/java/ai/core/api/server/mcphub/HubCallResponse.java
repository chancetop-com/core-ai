package ai.core.api.server.mcphub;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class HubCallResponse {
    @Property(name = "call_id")
    public String callId;

    @Property(name = "success")
    public Boolean success;

    @Property(name = "is_error")
    public Boolean isError;

    @Property(name = "content")
    public List<HubContentPart> content;

    @Property(name = "text")
    public String text;

    @Property(name = "duration_ms")
    public Long durationMs;

    @Property(name = "server_state")
    public String serverState;
}
