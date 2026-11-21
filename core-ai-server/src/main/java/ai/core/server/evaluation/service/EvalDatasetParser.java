package ai.core.server.evaluation.service;

import ai.core.server.evaluation.EvalHashing;
import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.time.OffsetDateTime;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Parses the v1 JSONL case format into the immutable case body.
 *
 * @author stephen
 */
@SuppressWarnings("PMD.ExceptionAsFlowControl")
public class EvalDatasetParser {
    public static final int MAX_CASES = 1000;
    public static final int MAX_LINE_BYTES = 1024 * 1024;
    public static final int MAX_CONTENT_BYTES = 20 * 1024 * 1024;
    private static final Set<String> ROOT_FIELDS = Set.of("case_id", "input", "reference", "grader_refs",
        "grader_parameters_json", "environment", "metadata");
    private static final Set<String> INPUT_FIELDS = Set.of("text", "variables", "attachments");
    private static final Set<String> REFERENCE_FIELDS = Set.of("output", "answers", "rubric", "assertions");
    private static final Set<String> ASSERTION_FIELDS = Set.of("kind", "path", "json_pointer", "expected_number",
        "tolerance", "expected_string", "expected_boolean");
    private static final Set<String> ENVIRONMENT_FIELDS = Set.of("fixture_ref", "clock");
    private static final Set<String> METADATA_FIELDS = Set.of("tags", "split", "weight", "source", "note");
    private static final Set<String> SPLITS = Set.of("DEV", "VALIDATION", "HOLDOUT");

    public ParsedDataset parse(String content) {
        if (content == null || content.isBlank()) throw new EvalValidationException("content", "dataset is empty");
        if (content.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_CONTENT_BYTES) {
            throw new EvalValidationException("content", "dataset exceeds " + MAX_CONTENT_BYTES + " bytes");
        }
        var result = new ParsedDataset();
        var seen = new HashSet<String>();
        var lines = content.split("\\r?\\n", -1);
        int lineNumber = 0;
        for (var raw : lines) {
            lineNumber++;
            var line = raw.trim();
            if (line.isEmpty()) continue;
            if (line.getBytes(java.nio.charset.StandardCharsets.UTF_8).length > MAX_LINE_BYTES) {
                throw new EvalValidationException("line " + lineNumber, "case line exceeds " + MAX_LINE_BYTES + " bytes");
            }
            try {
                var body = parseLine(line);
                if (!seen.add(body.caseId)) {
                    throw new EvalValidationException("case_id", "duplicate case_id: " + body.caseId);
                }
                result.cases.add(new ParsedCase(body.caseId,
                    EvalHashing.hashObject(body),
                    body.metadata == null || body.metadata.split == null ? EvalEnums.Split.DEV.name() : body.metadata.split,
                    body.metadata == null || body.metadata.weight == null ? 1.0 : body.metadata.weight,
                    JsonUtil.toJson(body)));
            } catch (EvalValidationException e) {
                throw new EvalValidationException("line " + lineNumber + "." + e.field, e.getMessage(), e);
            } catch (RuntimeException e) {
                throw new EvalValidationException("line " + lineNumber, "invalid case: " + e.getMessage(), e);
            }
            if (result.cases.size() > MAX_CASES) {
                throw new EvalValidationException("cases", "dataset exceeds " + MAX_CASES + " cases");
            }
        }
        if (result.cases.isEmpty()) throw new EvalValidationException("content", "dataset has no cases");
        return result;
    }

    private EvalBodies.CaseBody parseLine(String line) {
        JsonNode root;
        try {
            root = JsonUtil.OBJECT_MAPPER.readTree(line);
        } catch (Exception e) {
            throw new EvalValidationException("json", "invalid JSON: " + e.getMessage(), e);
        }
        requireObject(root, "case");
        rejectUnknown(root, ROOT_FIELDS, "case");
        var body = new EvalBodies.CaseBody();
        body.caseId = requiredText(root, "case_id");
        if (body.caseId.isBlank()) throw new EvalValidationException("case_id", "case_id must not be blank");
        if (!root.has("input")) throw new EvalValidationException("input", "input is required");
        body.input = parseInput(root.get("input"));
        if (root.has("reference") && !root.get("reference").isNull()) body.reference = parseReference(root.get("reference"));
        body.graderRefs = stringList(root.get("grader_refs"), "grader_refs", true);
        if (root.has("grader_parameters_json") && !root.get("grader_parameters_json").isNull()) {
            var text = requiredText(root, "grader_parameters_json");
            try {
                JsonUtil.OBJECT_MAPPER.readTree(text);
            } catch (Exception e) {
                throw new EvalValidationException("grader_parameters_json", "must be valid JSON text", e);
            }
            body.graderParametersJson = text;
        }
        if (root.has("environment") && !root.get("environment").isNull()) body.environment = parseEnvironment(root.get("environment"));
        if (root.has("metadata") && !root.get("metadata").isNull()) body.metadata = parseMetadata(root.get("metadata"));
        if (body.metadata == null) {
            body.metadata = new EvalBodies.CaseMetadata();
            body.metadata.split = EvalEnums.Split.DEV.name();
            body.metadata.weight = 1.0;
        } else {
            if (body.metadata.split == null) body.metadata.split = EvalEnums.Split.DEV.name();
            if (body.metadata.weight == null) body.metadata.weight = 1.0;
        }
        return body;
    }

    private EvalBodies.CaseInput parseInput(JsonNode node) {
        requireObject(node, "input");
        rejectUnknown(node, INPUT_FIELDS, "input");
        var input = new EvalBodies.CaseInput();
        input.text = requiredText(node, "text");
        if (input.text.isBlank()) throw new EvalValidationException("input.text", "input.text must not be blank");
        if (node.has("variables") && !node.get("variables").isNull()) {
            var variablesNode = node.get("variables");
            requireObject(variablesNode, "input.variables");
            var variables = new HashMap<String, String>();
            var iterator = variablesNode.properties().iterator();
            while (iterator.hasNext()) {
                var entry = iterator.next();
                if (!entry.getValue().isTextual()) {
                    throw new EvalValidationException("input.variables." + entry.getKey(), "must be a string");
                }
                variables.put(entry.getKey(), entry.getValue().textValue());
            }
            input.variables = variables;
        }
        if (node.has("attachments") && !node.get("attachments").isNull()) {
            var attachments = node.get("attachments");
            if (!attachments.isArray() || !attachments.isEmpty()) {
                throw new EvalValidationException("input.attachments", "attachments are not supported in this phase");
            }
        }
        return input;
    }

    private EvalBodies.CaseReference parseReference(JsonNode node) {
        requireObject(node, "reference");
        rejectUnknown(node, REFERENCE_FIELDS, "reference");
        var reference = new EvalBodies.CaseReference();
        if (node.has("output") && !node.get("output").isNull()) reference.output = requiredText(node, "output");
        if (node.has("answers") && !node.get("answers").isNull()) reference.answers = stringList(node.get("answers"), "reference.answers", false);
        if (node.has("rubric") && !node.get("rubric").isNull()) reference.rubric = requiredText(node, "rubric");
        if (node.has("assertions") && !node.get("assertions").isNull()) {
            var assertions = node.get("assertions");
            if (!assertions.isArray()) throw new EvalValidationException("reference.assertions", "must be an array");
            reference.assertions = new ArrayList<>(assertions.size());
            for (var i = 0; i < assertions.size(); i++) {
                reference.assertions.add(parseAssertion(assertions.get(i), i));
            }
        }
        if (reference.output == null && reference.answers == null && reference.rubric == null && reference.assertions == null) {
            throw new EvalValidationException("reference", "reference requires output, answers, rubric or assertions");
        }
        return reference;
    }

    private EvalBodies.EvalAssertion parseAssertion(JsonNode node, int index) {
        var field = "reference.assertions[" + index + "]";
        requireObject(node, field);
        rejectUnknown(node, ASSERTION_FIELDS, field);
        var assertion = new EvalBodies.EvalAssertion();
        assertion.kind = requiredText(node, "kind");
        if (!Set.of("JSON_EXISTS", "JSON_NUMBER_EQUALS", "JSON_STRING_EQUALS", "JSON_BOOLEAN_EQUALS").contains(assertion.kind)) {
            throw new EvalValidationException(field + ".kind", "unsupported assertion kind: " + assertion.kind);
        }
        assertion.jsonPointer = node.has("json_pointer") && !node.get("json_pointer").isNull()
            ? requiredText(node, "json_pointer") : null;
        if (assertion.jsonPointer != null && !assertion.jsonPointer.startsWith("/")) {
            throw new EvalValidationException(field + ".json_pointer", "must start with /");
        }
        if (node.has("path") && !node.get("path").isNull() && !node.get("path").asText().isBlank()) {
            throw new EvalValidationException(field + ".path", "artifact paths are not supported in this phase");
        }
        if ("JSON_NUMBER_EQUALS".equals(assertion.kind)) {
            if (!node.has("expected_number") || !node.get("expected_number").isNumber()) {
                throw new EvalValidationException(field + ".expected_number", "number is required");
            }
            assertion.expectedNumber = node.get("expected_number").doubleValue();
            assertion.tolerance = optionalNumber(node, "tolerance", field);
        }
        if ("JSON_STRING_EQUALS".equals(assertion.kind)) {
            if (!node.has("expected_string") || !node.get("expected_string").isTextual()) {
                throw new EvalValidationException(field + ".expected_string", "string is required");
            }
            assertion.expectedString = node.get("expected_string").textValue();
        }
        if ("JSON_BOOLEAN_EQUALS".equals(assertion.kind)) {
            if (!node.has("expected_boolean") || !node.get("expected_boolean").isBoolean()) {
                throw new EvalValidationException(field + ".expected_boolean", "boolean is required");
            }
            assertion.expectedBoolean = node.get("expected_boolean").booleanValue();
        }
        return assertion;
    }

    private Double optionalNumber(JsonNode node, String fieldName, String prefix) {
        if (!node.has(fieldName) || node.get(fieldName).isNull()) return null;
        if (!node.get(fieldName).isNumber()) {
            throw new EvalValidationException(prefix + "." + fieldName, "must be a number");
        }
        return node.get(fieldName).doubleValue();
    }

    private EvalBodies.CaseEnvironment parseEnvironment(JsonNode node) {
        requireObject(node, "environment");
        rejectUnknown(node, ENVIRONMENT_FIELDS, "environment");
        var environment = new EvalBodies.CaseEnvironment();
        if (node.has("fixture_ref") && !node.get("fixture_ref").isNull()) {
            throw new EvalValidationException("environment.fixture_ref", "fixtures are not supported in this phase");
        }
        if (node.has("clock") && !node.get("clock").isNull()) {
            environment.clock = requiredText(node, "clock");
            try {
                OffsetDateTime.parse(environment.clock);
            } catch (DateTimeParseException e) {
                throw new EvalValidationException("environment.clock", "must be ISO-8601 with offset", e);
            }
        }
        return environment;
    }

    private EvalBodies.CaseMetadata parseMetadata(JsonNode node) {
        requireObject(node, "metadata");
        rejectUnknown(node, METADATA_FIELDS, "metadata");
        var metadata = new EvalBodies.CaseMetadata();
        if (node.has("tags") && !node.get("tags").isNull()) metadata.tags = stringList(node.get("tags"), "metadata.tags", false);
        if (node.has("split") && !node.get("split").isNull()) {
            metadata.split = requiredText(node, "split");
            if (!SPLITS.contains(metadata.split)) {
                throw new EvalValidationException("metadata.split", "expected DEV|VALIDATION|HOLDOUT");
            }
        }
        if (node.has("weight") && !node.get("weight").isNull()) {
            if (!node.get("weight").isNumber()) throw new EvalValidationException("metadata.weight", "must be a number");
            metadata.weight = node.get("weight").doubleValue();
            if (metadata.weight <= 0 || Double.isInfinite(metadata.weight)) {
                throw new EvalValidationException("metadata.weight", "must be a positive finite number");
            }
        }
        if (node.has("source") && !node.get("source").isNull()) metadata.source = requiredText(node, "source");
        if (node.has("note") && !node.get("note").isNull()) metadata.note = requiredText(node, "note");
        return metadata;
    }

    private void requireObject(JsonNode node, String field) {
        if (node == null || !node.isObject()) throw new EvalValidationException(field, "must be a JSON object");
    }

    private void rejectUnknown(JsonNode node, Set<String> allowed, String prefix) {
        var keys = node.fieldNames();
        while (keys.hasNext()) {
            var key = keys.next();
            if (!allowed.contains(key)) throw new EvalValidationException(prefix + "." + key, "unknown field");
        }
    }

    private String requiredText(JsonNode node, String field) {
        if (!node.has(field) || node.get(field).isNull() || !node.get(field).isTextual()) {
            throw new EvalValidationException(field, "string is required");
        }
        return node.get(field).textValue();
    }

    private List<String> stringList(JsonNode node, String field, boolean required) {
        if (node == null || node.isNull()) {
            if (required) throw new EvalValidationException(field, "array is required");
            return null;
        }
        if (!node.isArray()) throw new EvalValidationException(field, "must be an array of strings");
        var values = new ArrayList<String>();
        for (var item : node) {
            if (!item.isTextual() || item.textValue().isBlank()) {
                throw new EvalValidationException(field, "must contain non-blank strings");
            }
            values.add(item.textValue());
        }
        if (required && values.isEmpty()) throw new EvalValidationException(field, "must not be empty");
        return values;
    }

    public static class ParsedDataset {
        public final List<ParsedCase> cases = new ArrayList<>();
    }

    public record ParsedCase(String caseId, String caseHash, String split, double weight, String bodyJson) {
    }
}
