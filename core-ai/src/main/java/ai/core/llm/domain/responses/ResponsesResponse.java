package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

public class ResponsesResponse {
    @Property(name = "id")
    public String id;
    @Property(name = "object")
    public String object = "response";
    @Property(name = "created_at")
    public Long createdAt;
    @Property(name = "completed_at")
    public Long completedAt;
    @Property(name = "status")
    public String status;
    @Property(name = "error")
    public ResponsesError error;
    @Property(name = "incomplete_details")
    public Map<String, String> incompleteDetails;
    @Property(name = "instructions")
    public String instructions;
    @Property(name = "max_output_tokens")
    public Integer maxOutputTokens;
    @Property(name = "model")
    public String model;
    @Property(name = "output")
    public List<ResponsesOutputItem> output;
    @Property(name = "parallel_tool_calls")
    public Boolean parallelToolCalls;
    @Property(name = "previous_response_id")
    public String previousResponseId;
    @Property(name = "reasoning")
    public ResponsesReasoning reasoning;
    @Property(name = "store")
    public Boolean store;
    @Property(name = "temperature")
    public Double temperature;
    @Property(name = "text")
    public Object text;
    @Property(name = "tool_choice")
    public Object toolChoice;
    @Property(name = "tools")
    public List<ResponsesTool> tools;
    @Property(name = "top_p")
    public Double topP;
    @Property(name = "truncation")
    public String truncation;
    @Property(name = "usage")
    public ResponsesUsage usage;
    @Property(name = "user")
    public String user;
    @Property(name = "metadata")
    public Map<String, String> metadata;
}
