package ai.core.benchmark.domain;

import com.fasterxml.jackson.annotation.JsonProperty;
import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * author: lim chen
 * date: 2025/12/19
 * description:
 */
public class GaiaItem {
    @JsonProperty("task_id")
    String taskId;
    @JsonProperty("Level")
    String question;
    @JsonProperty("Question")
    Integer level;
    @JsonProperty("file_name")
    String fileName;
    @JsonProperty("file_path")
    String filePath;

    public static GaiaItem fromJson(String json) {
        ObjectMapper mapper = new ObjectMapper();
        try {
            return mapper.readValue(json, GaiaItem.class);
        } catch (JsonProcessingException e) {
            throw new RuntimeException(e);
        }
    }



}
