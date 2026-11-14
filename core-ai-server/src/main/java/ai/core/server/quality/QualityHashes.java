package ai.core.server.quality;

import ai.core.server.domain.AgentPublishedConfig;
import ai.core.server.quality.domain.StoredQualityIssue;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import core.framework.crypto.Hash;

import java.util.ArrayList;
import java.util.List;

/**
 * Stable identities for "what was checked" and "what will be visible". Map entries are sorted recursively
 * so a rebuilt variables map with a different iteration order is still the same candidate.
 *
 * @author core-ai
 */
public final class QualityHashes {
    public static String candidateHash(AgentPublishedConfig config) {
        return "sha256:" + Hash.sha256Hex(canonical(config));
    }

    public static String metadataHash(String name, String description) {
        return "sha256:" + Hash.sha256Hex("name=" + nullToEmpty(name) + "\ndescription=" + nullToEmpty(description));
    }

    public static String textHash(String text) {
        return "sha256:" + Hash.sha256Hex(nullToEmpty(text));
    }

    public static String issueDigest(List<StoredQualityIssue> issues) {
        if (issues == null || issues.isEmpty()) return "sha256:" + Hash.sha256Hex("[]");
        var summary = new StringBuilder();
        for (var issue : issues) {
            summary.append(issue.ruleId).append('@').append(issue.ruleRevision).append(':')
                .append(issue.status).append(':').append(issue.enforcement).append(':')
                .append(issue.issueReason).append('\n');
        }
        return "sha256:" + Hash.sha256Hex(summary.toString());
    }

    private static String canonical(Object value) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.valueToTree(value);
            return JsonUtil.OBJECT_MAPPER.writeValueAsString(sorted(node));
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException("failed to canonicalize quality candidate", e);
        }
    }

    @edu.umd.cs.findbugs.annotations.SuppressFBWarnings("CFS_CONFUSING_FUNCTION_SEMANTICS")
    private static JsonNode sorted(JsonNode node) {
        if (node instanceof ObjectNode object) {
            var fields = new ArrayList<String>();
            object.fieldNames().forEachRemaining(fields::add);
            fields.sort(String::compareTo);
            var result = JsonUtil.OBJECT_MAPPER.createObjectNode();
            for (var field : fields) result.set(field, sorted(object.get(field)));
            return result;
        }
        if (node instanceof ArrayNode array) {
            var result = JsonUtil.OBJECT_MAPPER.createArrayNode();
            array.forEach(item -> result.add(sorted(item)));
            return result;
        }
        return node;
    }

    private static String nullToEmpty(String value) {
        return value == null ? "" : value;
    }

    private QualityHashes() {
    }
}
