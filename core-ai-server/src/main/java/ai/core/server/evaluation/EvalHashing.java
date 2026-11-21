package ai.core.server.evaluation;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.TreeMap;

/**
 * Versioned canonical JSON hashing used by evaluation fingerprints.
 *
 * @author stephen
 */
public final class EvalHashing {
    public static final String CANONICAL_VERSION = "core-ai-eval-canonical/v1";

    public static String hashJson(String json) {
        if (json == null) throw new IllegalArgumentException("json is required");
        try {
            JsonNode node = JsonUtil.OBJECT_MAPPER.readTree(json);
            var canonical = new StringBuilder(json.length() + 32);
            write(node, canonical);
            return sha256Hex(canonical.toString());
        } catch (IOException e) {
            throw new IllegalArgumentException("invalid JSON for fingerprint: " + e.getMessage(), e);
        }
    }

    public static String hashObject(Object value) {
        return hashJson(JsonUtil.toJson(value));
    }

    public static String sha256Hex(String value) {
        return sha256Hex(value.getBytes(StandardCharsets.UTF_8));
    }

    public static String sha256Hex(byte[] value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(value));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void write(JsonNode node, StringBuilder out) {
        if (node == null || node.isNull() || node.isMissingNode()) {
            out.append("null");
        } else if (node.isObject()) {
            var fields = new TreeMap<String, JsonNode>();
            for (var entry : node.properties()) {
                fields.put(entry.getKey(), entry.getValue());
            }
            out.append('{');
            var first = true;
            for (var entry : fields.entrySet()) {
                if (!first) out.append(',');
                first = false;
                writeText(entry.getKey(), out);
                out.append(':');
                write(entry.getValue(), out);
            }
            out.append('}');
        } else if (node.isArray()) {
            out.append('[');
            for (var i = 0; i < node.size(); i++) {
                if (i > 0) out.append(',');
                write(node.get(i), out);
            }
            out.append(']');
        } else if (node.isTextual()) {
            writeText(node.textValue(), out);
        } else if (node.isBoolean()) {
            out.append(node.booleanValue());
        } else if (node.isNumber()) {
            writeNumber(node, out);
        } else {
            out.append(node.toString());
        }
    }

    private static void writeNumber(JsonNode node, StringBuilder out) {
        if (node.isIntegralNumber()) {
            out.append(node.bigIntegerValue());
            return;
        }
        BigDecimal value = node.decimalValue().stripTrailingZeros();
        if (value.scale() <= 0) {
            out.append(value.toBigInteger());
        } else {
            out.append(value.toPlainString());
        }
    }

    private static void writeText(String value, StringBuilder out) {
        out.append('"');
        for (var i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    /** Sorted set hash for unordered ID collections. */
    public static String hashIdSet(List<String> ids) {
        if (ids == null || ids.isEmpty()) return hashJson("[]");
        List<String> sorted = new ArrayList<>(ids);
        sorted.sort(String::compareTo);
        return hashObject(sorted);
    }

    private EvalHashing() {
    }
}
