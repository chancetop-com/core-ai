package ai.core.benchmark.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.util.List;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public class BFCLItemGroundTruth {
    @JsonProperty("id")
    public String id;
    @JsonProperty("ground_truth")
    public JsonNode groundTruth;
    @JsonProperty("source")
    public List<Source> source;

    @JsonProperty("num_hops")
    public Integer numHops;

    public static BFCLItemGroundTruth fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, BFCLItemGroundTruth.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }

    public static class Source {
        @JsonProperty("subquestion")
        public String subquestion;
        @JsonProperty("answer")
        public String answer;
        @JsonProperty("source")
        public String source;
    }

}
