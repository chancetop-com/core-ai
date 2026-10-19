package ai.core.api.server.hubmodels;

import core.framework.api.json.Property;

/**
 * One media model enabled on the gateway. {@code upstreamModel} lets CLI clients reuse the same
 * model-family parameter hints the server-side agent uses; it carries no credential.
 *
 * @author stephen
 */
public class HubMediaModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "upstreamModel")
    public String upstreamModel;
}
