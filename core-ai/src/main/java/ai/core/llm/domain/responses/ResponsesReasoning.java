package ai.core.llm.domain.responses;

import ai.core.llm.domain.ReasoningEffort;
import core.framework.api.json.Property;

public class ResponsesReasoning {
    @Property(name = "effort")
    public ReasoningEffort effort;
    @Property(name = "summary")
    public Object summary;
}
