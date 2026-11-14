package ai.core.api.server.agent;

import core.framework.api.json.Property;
import core.framework.api.validate.NotNull;

/**
 * @author core-ai
 */
public class TestAgentQualityRuleRequest {
    @NotNull
    @Property(name = "agent_id")
    public String agentId;

    @Property(name = "sample_name")
    public String sampleName;

    @Property(name = "description")
    public String description;

    @Property(name = "system_prompt")
    public String systemPrompt;

    @Property(name = "expected_result")
    public String expectedResult;
}
