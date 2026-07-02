package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

import java.util.List;

public class ResponsesOutputItem {
    @Property(name = "id")
    public String id;
    @Property(name = "type")
    public String type;
    @Property(name = "status")
    public String status;
    @Property(name = "role")
    public String role;
    @Property(name = "content")
    public List<ResponsesContentPart> content;
    @Property(name = "call_id")
    public String callId;
    @Property(name = "name")
    public String name;
    @Property(name = "arguments")
    public String arguments;
}
