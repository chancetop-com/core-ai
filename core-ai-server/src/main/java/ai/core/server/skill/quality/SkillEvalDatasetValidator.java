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
                "缺少 eval/eval.json，或它没有随包发布。", "创建 eval/eval.json 并把它加入 skill 目录。"));
            return new Validation(fatal, warnings, Map.of(), 0, null);
        }
        JsonNode root;
        try {
            root = JsonUtil.OBJECT_MAPPER.readTree(new String(bytes, StandardCharsets.UTF_8));
        } catch (Exception e) {
            fatal.add(new Problem("EVAL_SCHEMA_INVALID", "/eval/eval.json",
                "eval.json 不是合法 JSON：" + e.getMessage(), "修正 JSON 语法。"));
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
                "schema_version 必须为整数 1。", "设置 schema_version: 1。"));
        }
        var skill = root.path("skill").asText(null);
        if (skill == null || !skill.equals(candidate.name)) {
            fatal.add(problem("EVAL_SKILL_MISMATCH", "/eval/eval.json/skill",
                "eval.json 的 skill 与 frontmatter name 不一致。", "把 skill 设为 \"" + candidate.name + "\"。"));
        }
    }

    private Map<String, Integer> validateCases(JsonNode root, SkillPublishCandidate candidate,
                                               List<Problem> fatal, List<Problem> warnings) {
        var cases = root.path("cases");
        if (!cases.isArray() || cases.isEmpty() || cases.size() > 200) {
            fatal.add(problem("EVAL_CASES_INVALID", "/eval/eval.json/cases",
                "cases 必须是 1–200 个对象的数组。", "补充测试用例。"));
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
                "数据集只有 " + cases.size() + " 个用例，建议 10–20 个。", "补充更多正例、边界与失败用例。"));
        }
        validateFixtures(fixtures, candidate, fatal);
        return counts;
    }

    private void validateCase(JsonNode node, int index, Set<String> ids, Map<String, Integer> counts,
                              List<String> fixtures, CaseContext context) {
        var path = "/eval/eval.json/cases/" + index;
        if (!node.isObject()) {
            context.fatal().add(problem("EVAL_CASE_INVALID", path, "case 必须是对象。", "修正该 case。"));
            return;
        }
        var id = node.path("id").asText("");
        if (!CASE_ID.matcher(id).matches() || !ids.add(id)) {
            context.fatal().add(problem("EVAL_CASE_ID_INVALID", path + "/id",
                "case id 必须唯一且匹配 ^[a-z0-9][a-z0-9-]{1,63}$。", "使用如 trigger-001 的 id。"));
        }
        var dimension = node.path("dimension").asText("");
        if (!DIMENSIONS.contains(dimension)) {
            context.fatal().add(problem("EVAL_DIMENSION_UNKNOWN", path + "/dimension",
                "未知测试维度：" + dimension, "使用 7 个 canonical 维度之一。"));
            return;
        }
        counts.merge(dimension, 1, Integer::sum);
        validateInput(node.path("input"), path + "/input", fixtures, context.fatal());
        validateExpected(dimension, node.path("expected"), path + "/expected", context.fatal());
        validateAssertions(node.path("assertions"), path + "/assertions", context.fatal());
        if ("trigger_accuracy".equals(dimension) && node.path("expected").path("triggered").asBoolean(true)) {
            context.warnings().add(problem("EVAL_NO_NEGATIVE_TRIGGER", path + "/expected",
                "trigger_accuracy 还没有 negative control（triggered=false）。", "至少加入一个不应触发该 skill 的请求。"));
        }
    }

    private void validateInput(JsonNode input, String path, List<String> fixtures, List<Problem> fatal) {
        var request = input.path("user_request").asText("");
        if (request.isBlank() || PLACEHOLDER.matcher(request.trim()).matches()) {
            fatal.add(problem("EVAL_CASE_PLACEHOLDER", path + "/user_request",
                "user_request 为空或是占位符。", "写一条真实用户会说的话。"));
        }
        var fixturesNode = input.path("fixtures");
        if (fixturesNode.isArray()) {
            for (var fixture : fixturesNode) fixtures.add(fixture.asText(""));
        }
    }

    private void validateExpected(String dimension, JsonNode expected, String path, List<Problem> fatal) {
        if (!expected.isObject() || expected.isEmpty()) {
            fatal.add(problem("EVAL_EXPECTED_INVALID", path, "expected 不能为空对象。", "填写该维度的期望结果。"));
            return;
        }
        var missing = missingExpectedKeys(dimension, expected);
        if (!missing.isEmpty()) {
            fatal.add(problem("EVAL_EXPECTED_INVALID", path,
                "expected 缺少该维度的必需 key：" + String.join(", ", missing),
                "按 eval 维度表补充 expected。"));
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
            fatal.add(problem("EVAL_ASSERTIONS_INVALID", path, "assertions 必须是 1–20 个断言。", "补充结构化断言。"));
            return;
        }
        for (var assertion : assertions) {
            var kind = assertion.path("kind").asText("");
            if (!ASSERTIONS.contains(kind)) {
                fatal.add(problem("EVAL_ASSERTION_UNKNOWN", path, "未知断言类型：" + kind,
                    "使用 eval 断言 allowlist 中的 kind。"));
            }
        }
    }

    private void validateFixtures(List<String> fixtures, SkillPublishCandidate candidate, List<Problem> fatal) {
        long total = 0;
        for (var fixture : fixtures) {
            var path = SkillDisclosureResolver.resolvePath(fixture);
            if (path == null || !path.startsWith("eval/fixtures/")) {
                fatal.add(problem("EVAL_FIXTURE_PATH_INVALID", "/eval/fixtures/" + fixture,
                    "fixture 路径必须位于 eval/fixtures/ 下：" + fixture, "把文件放到 eval/fixtures/。"));
                continue;
            }
            var bytes = candidate.resource(path);
            if (bytes == null) {
                fatal.add(problem("EVAL_FIXTURE_MISSING", "/eval/fixtures/" + fixture,
                    "fixture 未随包发布：" + fixture, "把文件加入 eval/fixtures/。"));
                continue;
            }
            if (bytes.length > MAX_FIXTURE_BYTES) {
                fatal.add(problem("EVAL_FIXTURE_TOO_LARGE", "/" + path,
                    "fixture 超过 10MB：" + fixture, "压缩或拆分 fixture。"));
            }
            total += bytes.length;
        }
        if (total > MAX_FIXTURES_BYTES) {
            fatal.add(problem("EVAL_FIXTURES_TOO_LARGE", "/eval/fixtures",
                "fixtures 总大小超过 20MB。", "缩减 fixture。"));
        }
    }

    private void validateCoverage(Map<String, Integer> counts, List<Problem> fatal, List<Problem> warnings) {
        for (var dimension : DIMENSIONS) {
            if (!counts.containsKey(dimension)) {
                fatal.add(problem("EVAL_DIMENSION_MISSING", "/eval/eval.json/cases",
                    "7 个维度缺少：" + dimension, "至少为该维度添加 1 个用例。"));
            }
        }
        for (var dimension : List.of("trigger_accuracy", "scope_accuracy", "robustness")) {
            var count = counts.getOrDefault(dimension, 0);
            if (count > 0 && count < 2) {
                warnings.add(problem("EVAL_DIMENSION_THIN", "/eval/eval.json/cases",
                    dimension + " 只有 " + count + " 个用例，建议至少 2 个。", "补充边界与失败用例。"));
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
