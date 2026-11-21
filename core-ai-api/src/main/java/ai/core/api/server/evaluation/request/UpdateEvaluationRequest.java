package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class UpdateEvaluationRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "description")
    public String description;
}
