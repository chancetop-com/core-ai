package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class GraderTestRequest {
    @Property(name = "body_json")
    public String bodyJson;
}
