package ai.core.api.server.hubmodels;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Media models enabled for the authenticated caller, split by endpoint: each list keeps the gateway's
 * priority order, so its first entry is the endpoint's default model.
 *
 * @author stephen
 */
public class ListHubMediaModelsResponse {
    @Property(name = "imageModels")
    public List<HubMediaModelView> imageModels;

    @Property(name = "imageEditModels")
    public List<HubMediaModelView> imageEditModels;

    @Property(name = "videoModels")
    public List<HubMediaModelView> videoModels;
}
