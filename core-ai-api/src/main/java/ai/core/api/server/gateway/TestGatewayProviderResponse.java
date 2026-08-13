package ai.core.api.server.gateway;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class TestGatewayProviderResponse {
    @Property(name = "ok")
    public Boolean ok;

    @Property(name = "status")
    public String status;

    @Property(name = "message")
    public String message;

    @Property(name = "durationMs")
    public Long durationMs;
}
