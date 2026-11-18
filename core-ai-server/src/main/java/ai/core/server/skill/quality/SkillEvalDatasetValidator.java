package ai.core.server.skill.quality;

import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;
import core.framework.crypto.Hash;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * Validates the eval dataset contract. It never runs the dataset: it proves the manifest is well formed,
 * covers all seven dimensions and references packaged fixtures.
 *
 * @author core-ai
 */
public final class SkillEvalDatasetValidator {
    public static final String DATASET_PATH = "eval/eval.json";
    private static final Set<String> DIMENSIONS = Set.of(
        "trigger_accuracy", "scope_accuracy", "tool_accuracy", "parameter_accuracy",
        "instruction_following", "output_quality", "robustness");
    private static final Set<String> ASSERTIONS = Set.of(
        "skill_invoked", "tool_called", "tool_not_called", "tool_args", "output_contains",
        "output_not_contains", "file_exists", "file_not_exists", "exit_code", "rubric", "no_crash");
    private static final Pattern CASE_ID = Pattern.compile("^[a-z0-9][a-z0-9-]{1,63}$");
    private static final Pattern PLACEHOLDER = Pattern.compile("^(todo|tbd|example|lorem ipsum|\\.{3,}|<[^>]+>)$",
        Pattern.CASE_INSENSITIVE);
    private static final long MAX_FIXTURE_BYTES = 10L * 1024 * 1024;
    private static final long MAX_FIXTURES_BYTES = 20L * 1024 * 1024;

    public Validation validate(SkillPublishCandidate candidate) {
        var fatal = new ArrayList<Problem>();
        var warnings = new ArrayList<Problem>();
        var bytes = candidate.resource(DATASET_PATH);
        if (bytes == null) {
            fatal.add(new Problem("EVAL_DATASET_NOT_PACKAGED", "/eval/eval.json",
                "eval/eval.json is missing or was not packaged.", "Create eval/eval.json and add it to the skill directory."));
            return new Validation(fatal, warnings, Map.of(), 0, null);
        }
        JsonNode root;
        try {
            root = JsonUtil.OBJECT_MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            fatal.add(new Problem("EVAL_SCHEMA_INVALID", "/eval/eval.json",
                "eval.json is not valid JSON: " + e.getMessage(), "Fix the JSON syntax."));
            return new Validation(fatal, warnings, Map.of(), 0, digest(bytes));
        }
        validateHeader(root, candidate, fatal);
        var counts = validateCases(root, candidate, fatal, warnings);
        validateCoverage(counts, fatal, warnings);
        return new Validation(fatal, warnings, counts, root.path("cases").size(), digest(bytes));
    }

    private void validateHeader(JsonNode root, SkillPublishCandidate candidate, List<Problem> fatal) {
        if (!root.isObject() || root.path("schema_version").asInt(-1) != 1) {
            fatal.add(problem("EVAL_SCHEMA_INVALID", "/eval/eval.json/schema_version",
                "schema_version must be integer 1.", "Set schema_version: 1."));
        }
        var skill = root.path("skill").asText(null);
        if (skill == null || !skill.equals(candidate.name)) {
            fatal.add(problem("EVAL_SKILL_MISMATCH", "/eval/eval.json/skill",
                "eval.json skill does not match the frontmatter name.", "Set skill to \"" + candidate.name + "\"。"));
        }
    }

    private Map<String, Integer> validateCases(JsonNode root, SkillPublishCandidate candidate,
                                               List<Problem> fatal, List<Problem> warnings) {
        var cases = root.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > 200) {
            fatal.add(problem("EVAL_CASES_INVALID", "/eval/eval.json/cases",
                "cases must be an array of 1-200 objects.", "Add test cases."));
            return Map.of();
        }
        var counts = new LinkedHashMap<String, Integer>();
        var ids = new LinkedHashSet<String>();
        var fixtures = new ArrayList<String>();
        var context = new CaseContext(fatal, warnings);
        for (int i = 0; i < cases.size(); i++) {
            validateCase(cases.get(i), i, ids, counts, fixtures, context);
        }
        if (cases.size() < 10) {
            warnings.add(problem("EVAL_DATASET_SMALL", "/eval/eval.json/cases",
                "The dataset has only " + cases.size() + " case(s); 10-20 are recommended.", "Add more positive, boundary and failure cases."));
        }
        validateFixtures(fixtures, candidate, fatal);
        return counts;
    }

    private void validateCase(JsonNode node, int index, Set<String> ids, Map<String, Integer> counts,
                              List<String> fixtures, CaseContext context) {
        var path = "/eval/eval.json/cases/" + index;
        if (!node.isObject()) {
            context.fatal().add(problem("EVAL_CASE_INVALID", path, "case must be an object.", "Fix this case."));
            return;
        }
        var id = node.path("id").asText("");
        if (!CASE_ID.matcher(id).matches() || !ids.add(id)) {
            context.fatal().add(problem("EVAL_CASE_ID_INVALID", path + "/id",
                "case id must be unique and match ^[a-z0-9][a-z0-9-]{1,63}$.", "Use an id such as trigger-001."));
        }
        var dimension = node.path("dimension").asText("");
        if (!DIMENSIONS.contains(dimension)) {
            context.fatal().add(problem("EVAL_DIMENSION_UNKNOWN", path + "/dimension",
                "Unknown test dimension: " + dimension, "Use one of the 7 canonical dimensions."));
            return;
        }
        counts.merge(dimension, 1, Integer::sum);
        validateInput(node.path("input"), path + "/input", fixtures, context.fatal());
        validateExpected(dimension, node.path("expected"), path + "/expected", context.fatal());
        validateAssertions(node.path("assertions"), path + "/assertions", context.fatal());
        if ("trigger_accuracy".equals(dimension) && node.path("expected").path("triggered").asBoolean(true)) {
            context.warnings().add(problem("EVAL_NO_NEGATIVE_TRIGGER", path + "/expected",
                "trigger_accuracy has no negative control (triggered=false).", "Add at least one request that should not trigger the skill."));
        }
    }

    private void validateInput(JsonNode input, String path, List<String> fixtures, List<Problem> fatal) {
        var request = input.path("user_request").asText("");
        if (request.isBlank() || PLACEHOLDER.matcher(request.trim()).matches()) {
            fatal.add(problem("EVAL_CASE_PLACEHOLDER", path + "/user_request",
                "user_request is empty or a placeholder.", "Write what a real user would say."));
        }
        var fixturesNode = input.path("fixtures");
        if (fixturesNode.isArray()) {
            for (var fixture : fixturesNode) fixtures.add(fixture.asText(""));
        }
    }

    private void validateExpected(String dimension, JsonNode expected, String path, List<Problem> fatal) {
        if (!expected.isObject() || expected.isEmpty()) {
            fatal.add(problem("EVAL_EXPECTED_INVALID", path, "expected must not be empty.", "Fill in the expected result for this dimension."));
            return;
        }
        var missing = missingExpectedKeys(dimension, expected);
        if (!missing.isEmpty()) {
            fatal.add(problem("EVAL_EXPECTED_INVALID", path,
                "expected is missing required keys for this dimension: " + String.join(", ", missing),
                "Add expected values per the eval dimension table."));
        }
    }

    private List<String> missingExpectedKeys(String dimension, JsonNode expected) {
        var missing = new ArrayList<String>();
        switch (dimension) {
            case "trigger_accuracy" -> requireBoolean(expected, "triggered", missing);
            case "scope_accuracy" -> {
                requireBoolean(expected, "in_scope", missing);
                requireText(expected, "expected_behavior", missing);
            }
            case "tool_accuracy" -> {
                if (!expected.has("tools") && !expected.has("no_tools")) missing.add("tools|no_tools");
            }
            case "parameter_accuracy" -> {
                requireText(expected, "tool", missing);
                if (!expected.has("arguments")) missing.add("arguments");
                requireText(expected, "match", missing);
            }
            case "instruction_following" -> {
                if (!expected.has("rules") && !expected.has("checks")) missing.add("rules|checks");
            }
            case "output_quality" -> {
                if (!expected.has("outputs") && !expected.has("rubric")) missing.add("outputs|rubric");
            }
            case "robustness" -> {
                requireText(expected, "failure_mode", missing);
                requireText(expected, "expected_behavior", missing);
            }
            default -> {
            }
        }
        return missing;
    }

    private void requireBoolean(JsonNode node, String key, List<String> missing) {
        if (!node.path(key).isBoolean()) missing.add(key + ":boolean");
    }

    private void requireText(JsonNode node, String key, List<String> missing) {
        if (node.path(key).asText("").isBlank()) missing.add(key + ":string");
    }

    private void validateAssertions(JsonNode assertions, String path, List<Problem> fatal) {
        if (!assertions.isArray() || assertions.isEmpty() || assertions.size() > 20) {
            fatal.add(problem("EVAL_ASSERTIONS_INVALID", path, "assertions must contain 1-20 assertions.", "Add structured assertions."));
            return;
        }
        for (var assertion : assertions) {
            var kind = assertion.path("kind").asText("");
            if (!ASSERTIONS.contains(kind)) {
                fatal.add(problem("EVAL_ASSERTION_UNKNOWN", path, "Unknown assertion kind: " + kind,
                    "Use a kind from the eval assertion allowlist."));
            }
        }
    }

    private void validateFixtures(List<String> fixtures, SkillPublishCandidate candidate, List<Problem> fatal) {
        long total = 0;
        for (var fixture : fixtures) {
            var path = SkillDisclosureResolver.resolvePath(fixture);
            if (path == null || !path.startsWith("eval/fixtures/")) {
                fatal.add(problem("EVAL_FIXTURE_PATH_INVALID", "/eval/fixtures/" + fixture,
                    "fixture path must be under eval/fixtures/: " + fixture, "Put the file under eval/fixtures/."));
                continue;
            }
            var bytes = candidate.resource(path);
            if (bytes == null) {
                fatal.add(problem("EVAL_FIXTURE_MISSING", "/eval/fixtures/" + fixture,
                    "fixture is not packaged: " + fixture, "Add the file under eval/fixtures/."));
                continue;
            }
            if (bytes.length > MAX_FIXTURE_BYTES) {
                fatal.add(problem("EVAL_FIXTURE_TOO_LARGE", "/" + path,
                    "fixture exceeds 10MB: " + fixture, "Compress or split the fixture."));
            }
            total += bytes.length;
        }
        if (total > MAX_FIXTURES_BYTES) {
            fatal.add(problem("EVAL_FIXTURES_TOO_LARGE", "/eval/fixtures",
                "fixtures total more than 20MB.", "Reduce the fixtures."));
        }
    }

    private void validateCoverage(Map<String, Integer> counts, List<Problem> fatal, List<Problem> warnings) {
        for (var dimension : DIMENSIONS) {
            if (!counts.containsKey(dimension)) {
                fatal.add(problem("EVAL_DIMENSION_MISSING", "/eval/eval.json/cases",
                    "Missing dimension: " + dimension, "Add at least one case for this dimension."));
            }
        }
        for (var dimension : List.of("trigger_accuracy", "scope_accuracy", "robustness")) {
            var count = counts.getOrDefault(dimension, 0);
            if (count > 0 && count < 2) {
                warnings.add(problem("EVAL_DIMENSION_THIN", "/eval/eval.json/cases",
                    dimension + " has only " + count + " case(s); at least 2 are recommended.", "Add boundary and failure cases."));
            }
        }
    }

    private Problem problem(String reason, String path, String message, String suggestion) {
        return new Problem(reason, path, message, suggestion);
    }

    private String digest(byte[] bytes) {
        return "sha256:" + Hash.sha256Hex(new String(bytes, StandardCharsets.UTF_8));
    }

    public record Problem(String reason, String fieldPath, String message, String suggestion) {
    }

    public record Validation(List<Problem> fatal, List<Problem> warnings, Map<String, Integer> dimensionCounts,
                             int caseCount, String datasetDigest) {
    }

    private record CaseContext(List<Problem> fatal, List<Problem> warnings) {
    }
}


