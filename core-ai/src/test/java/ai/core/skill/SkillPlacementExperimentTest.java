package ai.core.skill;

import ai.core.llm.domain.CompletionResponse;
import ai.core.utils.JsonUtil;
import core.framework.http.ContentType;
import core.framework.http.HTTPClient;
import core.framework.http.HTTPMethod;
import core.framework.http.HTTPRequest;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;

/**
 * Experiment: Where should skill descriptions be placed for optimal LLM tool-call accuracy?
 *
 * Groups:
 *   A - Skill info only in tool description
 *   B - Skill info only in system prompt
 *   C - Skill info in both (duplicated across locations)
 *   D - Tool desc has "what" (function), system prompt has "when" (trigger conditions)
 *   E - Skill info repeated 2x in tool description only (same-location repetition control)
 *   F - Skill info repeated 2x in system prompt only (same-location repetition control)
 *
 * Test set: 4 categories x multiple queries per skill
 *   - Positive: clearly should trigger a specific skill
 *   - Near-miss: semantically close but should NOT trigger
 *   - Ambiguous: could match multiple skills
 *   - Negative: unrelated, should not trigger any skill
 *
 * Run:
 *   ./gradlew :core-ai:test --tests "SkillPlacementExperimentTest"
 *
 * @author Xander
 */
@Disabled
class SkillPlacementExperimentTest {
    private static final Logger LOGGER = LoggerFactory.getLogger(SkillPlacementExperimentTest.class);

    private static String apiKey;
    private static String apiBase;
    private static HTTPClient httpClient;

    private static final List<String> MODELS = List.of(
            "anthropic/claude-sonnet-4.6"
    );

    private static final int RUNS_PER_CASE = 3;

    static final List<MockSkill> SKILLS = List.of(
            new MockSkill("code-review",
                    "Reviews code for bugs, security issues, and style problems. Supports multiple languages.",
                    "Use when user asks to review code, check for bugs, find security vulnerabilities, or improve code quality. Do NOT use for writing new code or refactoring."),
            new MockSkill("git-commit",
                    "Creates well-formatted git commits with conventional commit messages.",
                    "Use when user wants to commit changes, create a commit message, or stage and commit. Do NOT use for git log, git diff, or other read-only git operations."),
            new MockSkill("web-search",
                    "Searches the web for current information and returns summarized results.",
                    "Use when user needs real-time information, current events, or facts you don't know. Do NOT use for questions about the codebase or local files."),
            new MockSkill("test-generator",
                    "Generates unit tests for given code. Supports JUnit, pytest, jest frameworks.",
                    "Use when user explicitly asks to generate tests, create test cases, or write unit tests. Do NOT use for running existing tests or fixing test failures."),
            new MockSkill("sql-query",
                    "Writes and optimizes SQL queries. Supports MySQL, PostgreSQL, SQLite.",
                    "Use when user needs to write SQL, optimize a query, or design database schemas. Do NOT use for ORM code or application-level data access."),
            new MockSkill("doc-writer",
                    "Generates documentation including API docs, READMEs, and inline comments.",
                    "Use when user asks to write documentation, create a README, or generate API docs. Do NOT use for code comments that are part of a code-review."),
            new MockSkill("deploy",
                    "Manages deployment pipelines, creates Docker configs, and handles CI/CD setup.",
                    "Use when user asks about deployment, Docker, CI/CD pipelines, or production releases. Do NOT use for local development setup or environment configuration."),
            new MockSkill("refactor",
                    "Refactors code to improve structure, readability, and maintainability.",
                    "Use when user explicitly asks to refactor, restructure, or clean up code. Do NOT use for bug fixes or feature additions that happen to improve code."),
            new MockSkill("api-design",
                    "Designs RESTful and GraphQL APIs with proper schemas and endpoint structure.",
                    "Use when user asks to design an API, create endpoints, or define API contracts. Do NOT use for implementing API handlers or backend logic."),
            new MockSkill("perf-analyze",
                    "Analyzes code performance, identifies bottlenecks, and suggests optimizations.",
                    "Use when user asks about performance, profiling, benchmarking, or optimization. Do NOT use for general code review or functional bug fixes.")
    );

    static final List<TestCase> TEST_CASES = List.of(
            // --- Positive: clear skill match ---
            new TestCase("Review this Java class for potential bugs", QueryType.POSITIVE, "code-review", "direct match"),
            new TestCase("Can you check this code for security vulnerabilities?", QueryType.POSITIVE, "code-review", "security focus"),
            new TestCase("Commit these changes with a good message", QueryType.POSITIVE, "git-commit", "direct match"),
            new TestCase("Stage all modified files and create a commit", QueryType.POSITIVE, "git-commit", "multi-step"),
            new TestCase("What happened in the news today?", QueryType.POSITIVE, "web-search", "current events"),
            new TestCase("Search the web for the latest Python 3.13 features", QueryType.POSITIVE, "web-search", "explicit search"),
            new TestCase("Generate unit tests for this service class", QueryType.POSITIVE, "test-generator", "direct match"),
            new TestCase("Write JUnit tests for the UserController", QueryType.POSITIVE, "test-generator", "framework specific"),
            new TestCase("Write a SQL query to find top 10 customers by revenue", QueryType.POSITIVE, "sql-query", "direct match"),
            new TestCase("Optimize this slow PostgreSQL query", QueryType.POSITIVE, "sql-query", "optimization"),
            new TestCase("Write a README for this project", QueryType.POSITIVE, "doc-writer", "direct match"),
            new TestCase("Generate API documentation for these endpoints", QueryType.POSITIVE, "doc-writer", "api docs"),
            new TestCase("Set up a Docker deployment for this Spring Boot app", QueryType.POSITIVE, "deploy", "direct match"),
            new TestCase("Create a CI/CD pipeline with GitHub Actions", QueryType.POSITIVE, "deploy", "ci/cd"),
            new TestCase("Refactor this class to use the strategy pattern", QueryType.POSITIVE, "refactor", "direct match"),
            new TestCase("Clean up this spaghetti code and improve readability", QueryType.POSITIVE, "refactor", "cleanup"),
            new TestCase("Design a REST API for a todo app", QueryType.POSITIVE, "api-design", "direct match"),
            new TestCase("What should the API contract look like for user management?", QueryType.POSITIVE, "api-design", "contract"),
            new TestCase("Profile this method and find the bottleneck", QueryType.POSITIVE, "perf-analyze", "direct match"),
            new TestCase("Why is this endpoint so slow? Analyze performance.", QueryType.POSITIVE, "perf-analyze", "diagnosis"),

            // --- Near-miss: semantically close but wrong skill ---
            new TestCase("Show me the git log for the last 10 commits", QueryType.NEAR_MISS, null, "git read-only, not git-commit"),
            new TestCase("Run the existing tests and show results", QueryType.NEAR_MISS, null, "run tests, not generate tests"),
            new TestCase("Fix this failing test assertion", QueryType.NEAR_MISS, null, "fix test, not generate test"),
            new TestCase("Add input validation to this endpoint handler", QueryType.NEAR_MISS, null, "implement handler, not api-design"),
            new TestCase("Set up my local development environment", QueryType.NEAR_MISS, null, "local dev, not deploy"),
            new TestCase("Add a comment explaining this complex regex", QueryType.NEAR_MISS, null, "code comment during review, not doc-writer"),
            new TestCase("Fix this NullPointerException in the service layer", QueryType.NEAR_MISS, null, "bug fix, not code-review or refactor"),
            new TestCase("Write the JPA entity for this table", QueryType.NEAR_MISS, null, "ORM code, not sql-query"),

            // --- Ambiguous: could match multiple skills ---
            new TestCase("Review and refactor this authentication module", QueryType.AMBIGUOUS, "code-review,refactor", "review + refactor"),
            new TestCase("Write tests and document the payment service", QueryType.AMBIGUOUS, "test-generator,doc-writer", "test + doc"),
            new TestCase("Design and deploy a microservice API", QueryType.AMBIGUOUS, "api-design,deploy", "design + deploy"),
            new TestCase("Optimize this SQL query and profile the endpoint", QueryType.AMBIGUOUS, "sql-query,perf-analyze", "sql + perf"),
            new TestCase("Review the code and check for performance issues", QueryType.AMBIGUOUS, "code-review,perf-analyze", "review + perf"),

            // --- Negative: should not trigger any skill ---
            new TestCase("What is the capital of France?", QueryType.NEGATIVE, null, "general knowledge"),
            new TestCase("Explain how HashMap works in Java", QueryType.NEGATIVE, null, "explanation, no tool needed"),
            new TestCase("Calculate 42 * 17 + 3", QueryType.NEGATIVE, null, "math"),
            new TestCase("Tell me a joke about programming", QueryType.NEGATIVE, null, "entertainment"),
            new TestCase("What does this error message mean?", QueryType.NEGATIVE, null, "explanation"),
            new TestCase("How do I install Node.js on macOS?", QueryType.NEGATIVE, null, "setup instruction"),
            new TestCase("What is the difference between REST and GraphQL?", QueryType.NEGATIVE, null, "comparison/explanation")
    );

    static String buildSkillLines(List<MockSkill> skills) {
        var sb = new StringBuilder(1024);
        for (var skill : skills) {
            sb.append("- ").append(skill.name).append(": ").append(skill.whatDesc).append(' ').append(skill.whenDesc).append('\n');
        }
        return sb.toString();
    }

    static String buildToolDescGroupA() {
        return "Use a skill to accomplish specialized tasks.\nWhen a skill matches the user's request, call this tool.\n\nAvailable skills:\n" + buildSkillLines(SKILLS);
    }

    static String buildSystemPromptGroupA() {
        return "You are a helpful assistant with access to tools.";
    }

    static String buildToolDescGroupB() {
        return "Use a skill to accomplish specialized tasks. Call this tool with the skill name when appropriate.";
    }

    static String buildSystemPromptGroupB() {
        return "You are a helpful assistant with access to tools.\n\nYou have a use_skill tool. Available skills:\n"
                + buildSkillLines(SKILLS)
                + "\nWhen a skill matches the user's request, call use_skill with the name.";
    }

    static String buildToolDescGroupC() {
        return buildToolDescGroupA();
    }

    static String buildSystemPromptGroupC() {
        return buildSystemPromptGroupB();
    }

    static String buildToolDescGroupD() {
        var sb = new StringBuilder(512);
        sb.append("Use a skill to accomplish specialized tasks.\n\nAvailable skills (what each does):\n");
        for (var skill : SKILLS) {
            sb.append("- ").append(skill.name).append(": ").append(skill.whatDesc).append('\n');
        }
        return sb.toString();
    }

    static String buildSystemPromptGroupD() {
        var sb = new StringBuilder(512);
        sb.append("You are a helpful assistant with access to tools.\n\nSkill trigger conditions (when to call use_skill):\n");
        for (var skill : SKILLS) {
            sb.append("- ").append(skill.name).append(": ").append(skill.whenDesc).append('\n');
        }
        return sb.toString();
    }

    static String buildToolDescGroupE() {
        String lines = buildSkillLines(SKILLS);
        return "Use a skill to accomplish specialized tasks.\nWhen a skill matches the user's request, call this tool.\n\nAvailable skills:\n"
                + lines + "\nSkill reference (repeated for clarity):\n" + lines;
    }

    static String buildSystemPromptGroupE() {
        return "You are a helpful assistant with access to tools.";
    }

    static String buildToolDescGroupF() {
        return "Use a skill to accomplish specialized tasks. Call this tool with the skill name when appropriate.";
    }

    static String buildSystemPromptGroupF() {
        String lines = buildSkillLines(SKILLS);
        return "You are a helpful assistant with access to tools.\n\nYou have a use_skill tool. Available skills:\n"
                + lines + "\nSkill reference (repeated for clarity):\n" + lines
                + "\nWhen a skill matches the user's request, call use_skill with the name.";
    }

    @BeforeAll
    static void setup() throws IOException {
        var props = new Properties();
        try (var is = SkillPlacementExperimentTest.class.getResourceAsStream("/agent.properties")) {
            if (is != null) props.load(is);
        }
        apiKey = resolveProperty(props, "openrouter.api.key");
        apiBase = resolveProperty(props, "openrouter.api.base");
        if (apiBase == null || apiBase.isBlank()) {
            apiBase = "https://openrouter.ai/api/v1";
        }
        httpClient = HTTPClient.builder()
                .timeout(Duration.ofSeconds(120))
                .connectTimeout(Duration.ofSeconds(5))
                .trustAll()
                .build();
    }

    static String resolveProperty(Properties props, String key) {
        String value = props.getProperty(key);
        if (value == null) return null;
        if (value.startsWith("${") && value.endsWith("}")) {
            String envKey = value.substring(2, value.length() - 1);
            return System.getenv(envKey);
        }
        return value;
    }

    @Test
    void runExperiment() {
        LOGGER.info("========== SKILL PLACEMENT EXPERIMENT ==========");
        LOGGER.info("Models: {}", MODELS);
        LOGGER.info("Skills: {}", SKILLS.size());
        LOGGER.info("Test cases: {} (positive={}, near_miss={}, ambiguous={}, negative={})",
                TEST_CASES.size(),
                TEST_CASES.stream().filter(t -> t.type == QueryType.POSITIVE).count(),
                TEST_CASES.stream().filter(t -> t.type == QueryType.NEAR_MISS).count(),
                TEST_CASES.stream().filter(t -> t.type == QueryType.AMBIGUOUS).count(),
                TEST_CASES.stream().filter(t -> t.type == QueryType.NEGATIVE).count());
        LOGGER.info("Runs per case: {}", RUNS_PER_CASE);

        var groupToolDescs = new LinkedHashMap<String, String>();
        groupToolDescs.put("A", buildToolDescGroupA());
        groupToolDescs.put("B", buildToolDescGroupB());
        groupToolDescs.put("C", buildToolDescGroupC());
        groupToolDescs.put("D", buildToolDescGroupD());
        groupToolDescs.put("E", buildToolDescGroupE());
        groupToolDescs.put("F", buildToolDescGroupF());

        var groupSysPrompts = new LinkedHashMap<String, String>();
        groupSysPrompts.put("A", buildSystemPromptGroupA());
        groupSysPrompts.put("B", buildSystemPromptGroupB());
        groupSysPrompts.put("C", buildSystemPromptGroupC());
        groupSysPrompts.put("D", buildSystemPromptGroupD());
        groupSysPrompts.put("E", buildSystemPromptGroupE());
        groupSysPrompts.put("F", buildSystemPromptGroupF());

        var allResults = new LinkedHashMap<String, Map<String, Metrics>>();

        for (String model : MODELS) {
            LOGGER.info("\n====== MODEL: {} ======", model);
            var modelResults = new LinkedHashMap<String, Metrics>();
            for (String group : List.of("A", "B", "C", "D", "E", "F")) {
                LOGGER.info("--- Group {} ---", group);
                var metrics = runGroup(model, groupToolDescs.get(group), groupSysPrompts.get(group));
                modelResults.put(group, metrics);
            }
            allResults.put(model, modelResults);
        }

        printFinalReport(allResults);
    }

    private Metrics runGroup(String model, String toolDesc, String systemPrompt) {
        int[] counters = new int[7]; // tp, fp, fn, tn, ambiguousCorrect, ambiguousTotal, totalCalls
        for (var testCase : TEST_CASES) {
            List<String> responses = new ArrayList<>();
            for (int run = 0; run < RUNS_PER_CASE; run++) {
                responses.add(callAndExtractSkill(model, toolDesc, systemPrompt, testCase.query));
            }
            String majoritySkill = majorityVote(responses);
            evaluateTestCase(testCase, majoritySkill, counters);
        }
        return buildMetrics(counters);
    }

    // counters indices: 0=tp, 1=fp, 2=fn, 3=tn, 4=ambiguousCorrect, 5=ambiguousTotal, 6=totalCalls
    private void evaluateTestCase(TestCase testCase, String majoritySkill, int[] c) {
        c[6]++;
        switch (testCase.type) {
            case POSITIVE -> evaluatePositive(testCase, majoritySkill, c);
            case NEAR_MISS -> evaluateNegativeType(testCase, majoritySkill, c, "near_miss");
            case AMBIGUOUS -> evaluateAmbiguous(testCase, majoritySkill, c);
            case NEGATIVE -> evaluateNegativeType(testCase, majoritySkill, c, "negative");
            default -> LOGGER.warn("Unknown query type: {}", testCase.type);
        }
    }

    private void evaluatePositive(TestCase testCase, String majoritySkill, int[] c) {
        if (testCase.expectedSkill.equals(majoritySkill)) {
            c[0]++;
        } else if (majoritySkill == null) {
            c[2]++;
            LOGGER.warn("  MISS [positive] query=\"{}\" expected={} got=none", testCase.query, testCase.expectedSkill);
        } else {
            c[1]++;
            c[2]++;
            LOGGER.warn("  WRONG [positive] query=\"{}\" expected={} got={}", testCase.query, testCase.expectedSkill, majoritySkill);
        }
    }

    private void evaluateNegativeType(TestCase testCase, String majoritySkill, int[] c, String label) {
        if (majoritySkill == null) {
            c[3]++;
        } else {
            c[1]++;
            LOGGER.warn("  FALSE_TRIGGER [{}] query=\"{}\" got={} note={}", label, testCase.query, majoritySkill, testCase.note);
        }
    }

    private void evaluateAmbiguous(TestCase testCase, String majoritySkill, int[] c) {
        c[5]++;
        if (majoritySkill != null && testCase.expectedSkill.contains(majoritySkill)) {
            c[4]++;
            c[0]++;
        } else if (majoritySkill == null) {
            c[2]++;
        } else {
            c[1]++;
            LOGGER.warn("  WRONG [ambiguous] query=\"{}\" expected_one_of={} got={}", testCase.query, testCase.expectedSkill, majoritySkill);
        }
    }

    private Metrics buildMetrics(int[] counters) {
        int tp = counters[0];
        int fp = counters[1];
        int fn = counters[2];
        int tn = counters[3];
        int ambiguousCorrect = counters[4];
        int ambiguousTotal = counters[5];
        int totalCalls = counters[6];

        double precision = (tp + fp) > 0 ? (double) tp / (tp + fp) : 0;
        double recall = (tp + fn) > 0 ? (double) tp / (tp + fn) : 0;
        double f1 = (precision + recall) > 0 ? 2 * precision * recall / (precision + recall) : 0;
        double falseRate = totalCalls > 0 ? (double) fp / totalCalls : 0;
        double ambiguousRate = ambiguousTotal > 0 ? (double) ambiguousCorrect / ambiguousTotal : 0;

        var metrics = new Metrics(tp, fp, fn, tn, precision, recall, f1, falseRate, ambiguousRate);
        LOGGER.info("  TP={} FP={} FN={} TN={} P={} R={} F1={} FalseRate={} AmbiguousAcc={}",
                tp, fp, fn, tn,
                String.format("%.3f", precision),
                String.format("%.3f", recall),
                String.format("%.3f", f1),
                String.format("%.3f", falseRate),
                String.format("%.3f", ambiguousRate));
        return metrics;
    }

    private String callAndExtractSkill(String model, String toolDesc, String systemPrompt, String query) {
        var toolSchema = buildUseSkillToolSchema(toolDesc);
        var messages = List.of(
                Map.of("role", "system", "content", systemPrompt),
                Map.of("role", "user", "content", query)
        );

        var bodyMap = new HashMap<String, Object>();
        bodyMap.put("model", model);
        bodyMap.put("messages", messages);
        bodyMap.put("tools", List.of(toolSchema));
        bodyMap.put("temperature", 0.0);
        bodyMap.put("stream", false);

        var req = new HTTPRequest(HTTPMethod.POST, apiBase + "/chat/completions");
        req.headers.put("Content-Type", ContentType.APPLICATION_JSON.toString());
        req.headers.put("Authorization", "Bearer " + apiKey);
        req.body(JsonUtil.toJson(bodyMap).getBytes(StandardCharsets.UTF_8), ContentType.APPLICATION_JSON);

        try {
            var rsp = httpClient.execute(req);
            if (rsp.statusCode != 200) {
                LOGGER.error("API error ({}): {}", rsp.statusCode, rsp.text());
                return null;
            }
            return extractSkillFromResponse(rsp.text());
        } catch (Exception e) {
            LOGGER.error("Call failed for query=\"{}\" model={}: {}", query, model, e.getMessage());
            return null;
        }
    }

    private String extractSkillFromResponse(String responseText) {
        var response = JsonUtil.fromJson(CompletionResponse.class, responseText);
        var choice = response.choices.getFirst();
        if (choice.message.toolCalls != null && !choice.message.toolCalls.isEmpty()) {
            var toolCall = choice.message.toolCalls.getFirst();
            if ("use_skill".equals(toolCall.function.name)) {
                var args = JsonUtil.fromJson(Map.class, toolCall.function.arguments);
                return (String) args.get("name");
            }
        }
        return null;
    }

    private Map<String, Object> buildUseSkillToolSchema(String toolDesc) {
        return Map.of(
                "type", "function",
                "function", Map.of(
                        "name", "use_skill",
                        "description", toolDesc,
                        "parameters", Map.of(
                                "type", "object",
                                "properties", Map.of(
                                        "name", Map.of(
                                                "type", "string",
                                                "description", "Skill name to use"
                                        )
                                ),
                                "required", List.of("name"),
                                "additionalProperties", false
                        )
                )
        );
    }

    private String majorityVote(List<String> results) {
        Map<String, Integer> counts = new HashMap<>();
        int nullCount = 0;
        for (var r : results) {
            if (r == null) {
                nullCount++;
            } else {
                counts.merge(r, 1, Integer::sum);
            }
        }
        if (nullCount > results.size() / 2) return null;

        String best = null;
        int bestCount = 0;
        for (var entry : counts.entrySet()) {
            if (entry.getValue() > bestCount) {
                bestCount = entry.getValue();
                best = entry.getKey();
            }
        }
        return bestCount >= nullCount ? best : null;
    }

    private void printFinalReport(Map<String, Map<String, Metrics>> allResults) {
        LOGGER.info("\n\n========== FINAL REPORT ==========");
        LOGGER.info("{}", String.format("%-35s | %-10s | %-10s | %-10s | %-10s | %-12s | %-12s",
                "Model / Group", "Precision", "Recall", "F1", "FalseRate", "AmbiguousAcc", "TP/FP/FN/TN"));
        LOGGER.info("{}", "-".repeat(115));

        for (var modelEntry : allResults.entrySet()) {
            String model = modelEntry.getKey();
            for (var groupEntry : modelEntry.getValue().entrySet()) {
                String group = groupEntry.getKey();
                Metrics m = groupEntry.getValue();
                LOGGER.info("{}", String.format("%-35s | %-10s | %-10s | %-10s | %-10s | %-12s | %d/%d/%d/%d",
                        model + " [" + group + "]",
                        String.format("%.3f", m.precision),
                        String.format("%.3f", m.recall),
                        String.format("%.3f", m.f1),
                        String.format("%.3f", m.falseRate),
                        String.format("%.3f", m.ambiguousRate),
                        m.tp, m.fp, m.fn, m.tn));
            }
            LOGGER.info("{}", "-".repeat(115));
        }

        printBestGroupPerModel(allResults);
    }

    private void printBestGroupPerModel(Map<String, Map<String, Metrics>> allResults) {
        LOGGER.info("\n========== BEST GROUP PER MODEL ==========");
        for (var modelEntry : allResults.entrySet()) {
            String model = modelEntry.getKey();
            String bestF1Group = "";
            double bestF1 = -1;
            String lowestFalseGroup = "";
            double lowestFalse = 2;

            for (var groupEntry : modelEntry.getValue().entrySet()) {
                if (groupEntry.getValue().f1 > bestF1) {
                    bestF1 = groupEntry.getValue().f1;
                    bestF1Group = groupEntry.getKey();
                }
                if (groupEntry.getValue().falseRate < lowestFalse) {
                    lowestFalse = groupEntry.getValue().falseRate;
                    lowestFalseGroup = groupEntry.getKey();
                }
            }
            LOGGER.info("{}: Best F1 = Group {} ({}), Lowest False Rate = Group {} ({})",
                    model, bestF1Group, String.format("%.3f", bestF1),
                    lowestFalseGroup, String.format("%.3f", lowestFalse));
        }
    }

    record MockSkill(String name, String whatDesc, String whenDesc) { }

    enum QueryType { POSITIVE, NEAR_MISS, AMBIGUOUS, NEGATIVE }

    record TestCase(String query, QueryType type, String expectedSkill, String note) { }

    record Metrics(int tp, int fp, int fn, int tn,
                   double precision, double recall, double f1,
                   double falseRate, double ambiguousRate) { }
}
