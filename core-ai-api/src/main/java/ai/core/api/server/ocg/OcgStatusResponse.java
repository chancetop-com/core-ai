package ai.core.api.server.ocg;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class OcgStatusResponse {
    @Property(name = "status")
    public String status;

    @Property(name = "sandboxId")
    public String sandboxId;

    @Property(name = "sandboxIp")
    public String sandboxIp;
}
