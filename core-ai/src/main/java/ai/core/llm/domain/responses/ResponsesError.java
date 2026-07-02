package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

public class ResponsesError {
    @Property(name = "code")
    public String code;
    @Property(name = "message")
    public String message;
}
