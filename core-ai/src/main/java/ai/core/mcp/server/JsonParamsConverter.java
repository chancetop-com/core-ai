package ai.core.mcp.server;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.framework.util.Strings;

public class JsonParamsConverter {
    private static final ObjectMapper mapper = new ObjectMapper();

    public static String convert(String json) {
        try {
            JsonNode rootNode = mapper.readTree(json);
            if (!(rootNode instanceof ObjectNode root)) {
                throw new IllegalArgumentException("Root must be a JSON object");
            }

            JsonNode paramsNode = root.get("params");
            if (paramsNode != null && !paramsNode.isTextual()) {
                String paramsStr = mapper.writeValueAsString(paramsNode);
                root.put("params", paramsStr);
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON params", e);
        }
    }

    public static String revert(String json) {
        if (Strings.isBlank(json)) return json;
        try {
            JsonNode rootNode = mapper.readTree(json);
            if (!(rootNode instanceof ObjectNode root)) {
                throw new IllegalArgumentException("Root must be a JSON object");
            }

            JsonNode paramsNode = root.get("params");
            if (paramsNode != null && paramsNode.isTextual()) {
                JsonNode realParams = mapper.readTree(paramsNode.asText());
                root.set("params", realParams);
            }

            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to revert JSON params", e);
        }
    }
}
