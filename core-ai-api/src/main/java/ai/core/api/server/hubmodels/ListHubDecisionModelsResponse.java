package ai.core.api.server.hubmodels;

import core.framework.api.json.Property;

import java.util.List;

/**
 * Decision models enabled for the authenticated caller, in gateway priority order: the first entry is
 * the model a request without a model routes to.
 *
 * @author stephen
 */
public class ListHubDecisionModelsResponse {
    @Property(name = "models")
    public List<HubDecisionModelView> models;
}
