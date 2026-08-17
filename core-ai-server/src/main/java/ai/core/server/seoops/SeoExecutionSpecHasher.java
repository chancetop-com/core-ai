package ai.core.server.seoops;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.framework.web.exception.BadRequestException;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;

/**
 * @author xander
 */
public class SeoExecutionSpecHasher {
    private final ObjectMapper mapper = new ObjectMapper();

    public String canonicalize(String rawJson) {
        if (rawJson == null || rawJson.isBlank()) throw new BadRequestException("execution_spec is required");
        try {
            return mapper.writeValueAsString(sort(mapper.readTree(rawJson)));
        } catch (JsonProcessingException | IllegalArgumentException e) {
            throw new BadRequestException("execution_spec must be valid JSON", "INVALID_EXECUTION_SPEC", e);
        }
    }

    public String hash(String rawJson) {
        try {
            var bytes = MessageDigest.getInstance("SHA-256")
                .digest(canonicalize(rawJson).getBytes(StandardCharsets.UTF_8));
            return "sha256:" + HexFormat.of().formatHex(bytes);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private JsonNode sort(JsonNode value) {
        if (value.isObject()) {
            ObjectNode sorted = mapper.createObjectNode();
            var names = new ArrayList<String>();
            value.fieldNames().forEachRemaining(names::add);
            names.stream().sorted().forEach(name -> sorted.set(name, sort(value.get(name))));
            return sorted;
        }
        if (value.isArray()) {
            ArrayNode sorted = mapper.createArrayNode();
            value.forEach(item -> sorted.add(sort(item)));
            return sorted;
        }
        return value.deepCopy();
    }
}
