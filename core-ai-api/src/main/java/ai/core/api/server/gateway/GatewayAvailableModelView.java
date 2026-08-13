package ai.core.api.server.gateway;

import core.framework.api.json.Property;

import java.util.List;

/**
 * @author stephen
 */
public class GatewayAvailableModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "endpointTypes")
    public List<String> endpointTypes;

    @Property(name = "supportsVision")
    public Boolean supportsVision;

    @Property(name = "supportsVideo")
    public Boolean supportsVideo;

    @Property(name = "supportsFile")
    public Boolean supportsFile;
}
