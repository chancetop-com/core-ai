package ai.core.api.server.hubmodels;

import core.framework.api.json.Property;

/**
 * One decision model enabled on the gateway. Decision models are not chat models: clients use them for
 * structured verdicts, so they are listed on their own hub surface.
 *
 * @author stephen
 */
public class HubDecisionModelView {
    @Property(name = "modelId")
    public String modelId;

    @Property(name = "displayName")
    public String displayName;

    @Property(name = "providerName")
    public String providerName;

    @Property(name = "upstreamModel")
    public String upstreamModel;
}
