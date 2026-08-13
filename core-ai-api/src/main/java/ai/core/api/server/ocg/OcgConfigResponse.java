package ai.core.api.server.ocg;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class OcgConfigResponse {
    @Property(name = "config")
    public OcgConfigView config;
}
