package ai.core.benchmark.domain;

import core.framework.api.json.Property;

import java.util.List;
import java.util.Map;

public class BFCLItemEvalResult {
    public static BFCLItemEvalResult of(String id,
                                        List<Map<String, Object>> result,
                                        Integer inputTokenCount, Integer outputTokenCount,
                                        Double latency) {
        BFCLItemEvalResult agentResult = new BFCLItemEvalResult();
        agentResult.id = id;
        agentResult.result = result;
        agentResult.inputTokenCount = inputTokenCount;
        agentResult.outputTokenCount = outputTokenCount;
        agentResult.latency = latency;
        return agentResult;
    }

    @Property(name = "id")
    public String id;

    @Property(name = "result")
    public List<Map<String, Object>> result;

    @Property(name = "input_token_count")
    public Integer inputTokenCount;

    @Property(name = "output_token_count")
    public Integer outputTokenCount;

    @Property(name = "latency")
    public Double latency;
}
