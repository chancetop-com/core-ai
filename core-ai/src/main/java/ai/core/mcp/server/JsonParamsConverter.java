package ai.core.mcp.server;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * @author stephen
 */
public class JsonParamsConverter {
    public static String convert(String json) {
        try {
            var mapper = new ObjectMapper();
            var root = (ObjectNode) mapper.readTree(json);
            var paramsStr = mapper.writeValueAsString(root.get("params"));
            root.put("params", paramsStr);
            return mapper.writeValueAsString(root);
        } catch (Exception e) {
            throw new RuntimeException("Failed to convert JSON params", e);
        }
    }

    public static String revert(String text) {
        try {
            var mapper = new ObjectMapper();
            var node = mapper.readTree(text);
            return mapper.writeValueAsString(node);
        } catch (Exception e) {
            throw new RuntimeException("Failed to revert text params", e);
        }
    }
}
