package ai.core.benchmark.domain;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public class GaiaItemGroundTruth {
    @JsonProperty("task_id")
    String taskId;
    @JsonProperty("Annotator Metadata")
    AnnotatorMetadata annotatorMetadata;
    @JsonProperty("Final answer")
    String finalAnswer;

    public static class AnnotatorMetadata {
        @JsonProperty("Steps")
        String steps;

        @JsonProperty("Number of steps")
        String numberOfSteps;

        @JsonProperty("How long did this take?")
        String duration;

        @JsonProperty("Tools")
        String tools;

        @JsonProperty("Number of tools")
        String numberOfTools;
    }
}
