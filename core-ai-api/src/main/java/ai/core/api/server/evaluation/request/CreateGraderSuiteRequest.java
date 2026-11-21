package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateGraderSuiteRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "body_json")
    public String bodyJson;
}
