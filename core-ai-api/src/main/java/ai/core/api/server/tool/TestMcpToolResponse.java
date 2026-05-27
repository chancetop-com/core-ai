package ai.core.api.server.tool;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TestMcpToolResponse {
    @Property(name = "success")
    public Boolean success;

    @Property(name = "result")
    public String result;

    @Property(name = "duration_ms")
    public Long durationMs;
}
