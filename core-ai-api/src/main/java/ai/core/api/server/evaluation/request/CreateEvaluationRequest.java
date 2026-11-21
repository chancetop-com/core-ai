package ai.core.api.server.evaluation.request;

import core.framework.api.json.Property;

/**
 * @author stephen
 */
public class CreateEvaluationRequest {
    @Property(name = "name")
    public String name;

    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "description")
    public String description;
}
