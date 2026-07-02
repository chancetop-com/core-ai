package ai.core.llm.domain.responses;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

/**
 * DTO for the supported subset of OpenAI Responses create requests.
 */
public class ResponsesRequest {
    @Property(name = "model")
    public String model;
    @Property(name = "input")
    public Object input;
    @Property(name = "instructions")
    public String instructions;
    @Property(name = "stream")
    public Boolean stream;
    @Property(name = "temperature")
    public Double temperature;
    @Property(name = "top_p")
    public Double topP;
    @Property(name = "max_output_tokens")
    public Integer maxOutputTokens;
    @Property(name = "parallel_tool_calls")
    public Boolean parallelToolCalls;
    @Property(name = "tool_choice")
    public Object toolChoice;
    @Property(name = "tools")
    public List<ResponsesTool> tools;
    @Property(name = "text")
    public Object text;
    @Property(name = "reasoning")
    public ResponsesReasoning reasoning;

    @Property(name = "previous_response_id")
    public String previousResponseId;
    @Property(name = "conversation")
    public Object conversation;
    @Property(name = "background")
    public Boolean background;
    @Property(name = "prompt")
    public Object prompt;
    @Property(name = "truncation")
    public String truncation;
    @Property(name = "max_tool_calls")
    public Integer maxToolCalls;

    @Property(name = "store")
    public Boolean store;
    @Property(name = "include")
    public List<String> include;
    @Property(name = "metadata")
    public Map<String, String> metadata;
    @Property(name = "service_tier")
    public String serviceTier;
    @Property(name = "prompt_cache_key")
    public String promptCacheKey;
    @Property(name = "safety_identifier")
    public String safetyIdentifier;
    @Property(name = "user")
    public String user;
}
