package ai.core.api.server.ocg;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class ListOcgConfigsResponse {
    @Property(name = "configs")
    public List<OcgConfigView> configs;
}
