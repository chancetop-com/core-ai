package ai.core.api.server.sandbox;

import core.framework.api.json.Property;

/**
 * @author xander
 */
public class TerminalTicketResponse {
    @Property(name = "ticket")
    public String ticket;

    @Property(name = "gateway_url")
    public String gatewayUrl;
}
