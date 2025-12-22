package ai.core.benchmark.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.Map;

/**
 * author: lim chen
 * date: 2025/12/19
 * description: Agent execution result for BFCL benchmark item
 */
public class BFCLItemAgentResult {
    @JsonProperty("id")
    public String id;

    @JsonProperty("category")
    public String category;

    @JsonProperty("result")
    public List<Map<String, Object>> result;

    @JsonProperty("input_token_count")
    public Integer inputTokenCount;

    @JsonProperty("output_token_count")
    public Integer outputTokenCount;

    @JsonProperty("latency")
    public Double latency;


    public static BFCLItemAgentResult of(String id, String category,
                                              List<Map<String, Object>> result,
                                              Integer inputTokenCount, Integer outputTokenCount,
                                              Double latency) {
        BFCLItemAgentResult agentResult = new BFCLItemAgentResult();
        agentResult.id = id;
        agentResult.category = category;
        agentResult.result = result;
        agentResult.inputTokenCount = inputTokenCount;
        agentResult.outputTokenCount = outputTokenCount;
        agentResult.latency = latency;
        return agentResult;
    }

}
