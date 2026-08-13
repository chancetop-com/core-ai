package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class GatewayDiscoveredModelView {
    @Property(name = "id")
    public String id;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "endpointTypes")
    public List<String> endpointTypes;

    @Property(name = "contextWindow")
    public Long contextWindow;

    @Property(name = "supportsStream")
    public Boolean supportsStream;

    @Property(name = "supportsTools")
    public Boolean supportsTools;

    @Property(name = "supportsVision")
    public Boolean supportsVision;

    @Property(name = "supportsFile")
    public Boolean supportsFile;

    @Property(name = "inputPricePer1MTokens")
    public Double inputPricePer1MTokens;

    @Property(name = "outputPricePer1MTokens")
    public Double outputPricePer1MTokens;

    @Property(name = "imported")
    public Boolean imported;
}
