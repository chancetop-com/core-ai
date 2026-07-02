package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

import java.util.List;

public class ResponsesContentPart {
    @Property(name = "type")
    public String type;
    @Property(name = "text")
    public String text;
    @Property(name = "annotations")
    public List<Object> annotations;
}
