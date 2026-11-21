package ai.core.server.evaluation.grading;

import ai.core.server.evaluation.domain.EvalBodies;
import ai.core.server.evaluation.domain.EvalEnums;
import ai.core.utils.JsonUtil;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.IOException;
import java.io.Serial;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.regex.Pattern;

/**
 * Deterministic built-in graders for the first evaluation backend. Python and model graders are
 * deliberately absent: they need an isolated execution backend that this deployment does not yet have.
 *
 * @author stephen
 */
@SuppressWarnings("PMD.ExceptionAsFlowControl")
public class EvalGraderEngine {
    public static final String SUCCESS_POLICY = "ALL_APPLICABLE_REQUIRED_PASS";
    private static final int MAX_OUTPUT_LENGTH = 256 * 1024;
    private static final int MAX_PATTERN_LENGTH = 500;
    private static final Pattern NESTED_QUANTIFIER = Pattern.compile("\\([^)]*[+*][^)]*\\)[+*]");

    public EvalBodies.GradingBundle grade(EvalBodies.GraderSuiteBody suite, EvalBodies.CaseBody caseBody,
                                          String output, EvalEnums.ExecutionResult executionResult) {
        var bundle = new EvalBodies.GradingBundle();
        bundle.graderSuiteId = null;
        bundle.results = new ArrayList<>(8);
        if (suite == null || suite.graders == null) return bundle;
        var applicable = applicable(suite, caseBody);
        for (var grader : applicable) {
            bundle.results.add(gradeOne(grader, caseBody, output, executionResult));
        }
        return bundle;
    }

    public List<EvalBodies.GraderDefinition> applicable(EvalBodies.GraderSuiteBody suite, EvalBodies.CaseBody caseBody) {
        var result = new ArrayList<EvalBodies.GraderDefinition>();
        if (suite == null || suite.graders == null || caseBody == null || caseBody.graderRefs == null) return result;
        var byId = new HashMap<String, EvalBodies.GraderDefinition>();
        for (var grader : suite.graders) {
            if (grader != null && grader.id != null) byId.put(grader.id, grader);
        }
        for (var ref : caseBody.graderRefs) {
            var grader = byId.get(ref);
            if (grader != null) result.add(grader);
        }
        return result;
    }

    private EvalBodies.GraderResult gradeOne(EvalBodies.GraderDefinition grader, EvalBodies.CaseBody caseBody,
                                             String output, EvalEnums.ExecutionResult executionResult) {
        var result = new EvalBodies.GraderResult();
        result.graderId = grader.id;
        result.required = Boolean.TRUE.equals(grader.required);
        result.status = EvalEnums.GradingStatus.COMPLETED.name();
        result.metrics = new ArrayList<>();
        result.evidence = new ArrayList<>();
        long started = System.nanoTime();
        try {
            if (executionResult != EvalEnums.ExecutionResult.SUCCEEDED) {
                result.status = EvalEnums.GradingStatus.NOT_RUN_TARGET_FAILED.name();
                result.verdict = null;
                result.feedback = "target execution did not succeed: " + executionResult;
                return result;
            }
            if (output == null) {
                throw new GraderInputException("actual output is missing");
            }
            if (output.length() > MAX_OUTPUT_LENGTH) {
                throw new GraderInputException("actual output exceeds the grader input limit");
            }
            var kind = EvalEnums.GraderKind.valueOf(grader.kind);
            var passed = switch (kind) {
                case EXACT -> matchesReference(grader, caseBody, output, MatchMode.EXACT);
                case CONTAINS -> matchesReference(grader, caseBody, output, MatchMode.CONTAINS);
                case REGEX -> matchesRegex(grader, output);
                case JSON_ASSERTION -> matchesAssertions(caseBody, output, result);
            };
            applyVerdict(grader, result, passed ? 1.0 : 0.0);
        } catch (GraderInputException e) {
            result.status = EvalEnums.GradingStatus.ERROR.name();
            result.error = e.getMessage();
        } catch (IllegalArgumentException e) {
            result.status = EvalEnums.GradingStatus.ERROR.name();
            result.error = "invalid grader configuration: " + e.getMessage();
        } catch (RuntimeException e) {
            result.status = EvalEnums.GradingStatus.ERROR.name();
            result.error = "grader failed: " + e.getMessage();
        } finally {
            result.durationMs = (System.nanoTime() - started) / 1_000_000;
        }
        return result;
    }

    private boolean matchesReference(EvalBodies.GraderDefinition grader, EvalBodies.CaseBody caseBody,
                                     String output, MatchMode mode) {
        var reference = caseBody.reference;
        if (reference == null) throw new GraderInputException("reference is missing");
        var candidates = new ArrayList<String>();
        if (reference.output != null) candidates.add(reference.output);
        if (reference.answers != null) candidates.addAll(reference.answers);
        if (candidates.isEmpty()) throw new GraderInputException("reference.output or reference.answers is required for " + mode);
        var normalizedOutput = normalize(output, grader.normalization);
        for (var candidate : candidates) {
            var normalizedReference = normalize(candidate, grader.normalization);
            if (mode == MatchMode.EXACT && normalizedOutput.equals(normalizedReference)) return true;
            if (mode == MatchMode.CONTAINS && normalizedOutput.contains(normalizedReference)) return true;
        }
        return false;
    }

    private boolean matchesRegex(EvalBodies.GraderDefinition grader, String output) {
        var patternText = grader.pattern != null ? grader.pattern : grader.contains;
        if (patternText == null || patternText.isBlank()) throw new GraderInputException("regex pattern is required");
        validatePattern(patternText);
        var pattern = Pattern.compile(patternText);
        var normalized = normalize(output, grader.normalization);
        return pattern.matcher(normalized).find();
    }

    private boolean matchesAssertions(EvalBodies.CaseBody caseBody, String output, EvalBodies.GraderResult result) {
        var assertions = caseBody.reference == null ? null : caseBody.reference.assertions;
        if (assertions == null || assertions.isEmpty()) {
            throw new GraderInputException("reference.assertions is required for JSON_ASSERTION");
        }
        JsonNode actual;
        try {
            actual = JsonUtil.OBJECT_MAPPER.readTree(output);
        } catch (IOException e) {
            result.feedback = "actual output is not valid JSON";
            return false;
        }
        for (var assertion : assertions) {
            if (!checkAssertion(assertion, actual, result)) return false;
        }
        return true;
    }

    private boolean checkAssertion(EvalBodies.EvalAssertion assertion, JsonNode actual, EvalBodies.GraderResult result) {
        if (assertion.kind == null) throw new GraderInputException("assertion.kind is required");
        var evidence = new EvalBodies.Evidence();
        evidence.kind = "ACTUAL_JSON";
        evidence.jsonPointer = assertion.jsonPointer;
        var matched = switch (assertion.kind) {
            case "JSON_EXISTS" -> checkExists(assertion, actual, evidence);
            case "JSON_NUMBER_EQUALS" -> checkNumber(assertion, actual, evidence);
            case "JSON_STRING_EQUALS" -> checkString(assertion, actual, evidence);
            case "JSON_BOOLEAN_EQUALS" -> checkBoolean(assertion, actual, evidence);
            default -> throw new GraderInputException("unsupported assertion kind: " + assertion.kind);
        };
        if (!matched) result.evidence.add(evidence);
        return matched;
    }

    private boolean checkExists(EvalBodies.EvalAssertion assertion, JsonNode actual, EvalBodies.Evidence evidence) {
        var node = pointer(actual, assertion.jsonPointer);
        evidence.actual = node == null ? "absent" : node.toString();
        evidence.expected = "present";
        return node != null && !node.isNull();
    }

    private boolean checkNumber(EvalBodies.EvalAssertion assertion, JsonNode actual, EvalBodies.Evidence evidence) {
        var node = pointer(actual, assertion.jsonPointer);
        var expected = assertion.expectedNumber == null ? 0.0 : assertion.expectedNumber;
        var tolerance = assertion.tolerance == null ? 0.0 : Math.abs(assertion.tolerance);
        evidence.expected = String.valueOf(expected);
        evidence.actual = node == null ? "absent" : node.toString();
        return node != null && node.isNumber() && Math.abs(node.doubleValue() - expected) <= tolerance;
    }

    private boolean checkString(EvalBodies.EvalAssertion assertion, JsonNode actual, EvalBodies.Evidence evidence) {
        var node = pointer(actual, assertion.jsonPointer);
        evidence.expected = assertion.expectedString;
        evidence.actual = node == null ? "absent" : node.toString();
        return node != null && node.isTextual() && node.textValue().equals(assertion.expectedString);
    }

    private boolean checkBoolean(EvalBodies.EvalAssertion assertion, JsonNode actual, EvalBodies.Evidence evidence) {
        var node = pointer(actual, assertion.jsonPointer);
        evidence.expected = String.valueOf(assertion.expectedBoolean);
        evidence.actual = node == null ? "absent" : node.toString();
        return node != null && node.isBoolean()
            && node.booleanValue() == Boolean.TRUE.equals(assertion.expectedBoolean);
    }

    private void applyVerdict(EvalBodies.GraderDefinition grader, EvalBodies.GraderResult result, double passScore) {
        var mode = EvalEnums.GraderMode.valueOf(grader.mode == null ? "BOOLEAN" : grader.mode);
        if (mode == EvalEnums.GraderMode.BOOLEAN) {
            result.verdict = passScore > 0 ? EvalEnums.Verdict.PASS.name() : EvalEnums.Verdict.FAIL.name();
            result.score = passScore;
            return;
        }
        var failMax = grader.failMax == null ? 0.2 : grader.failMax;
        var passMin = grader.passMin == null ? 0.8 : grader.passMin;
        result.score = passScore;
        if (passScore <= failMax) {
            result.verdict = EvalEnums.Verdict.FAIL.name();
        } else if (passScore >= passMin) {
            result.verdict = EvalEnums.Verdict.PASS.name();
        } else {
            result.verdict = EvalEnums.Verdict.INDETERMINATE.name();
        }
    }

    private JsonNode pointer(JsonNode root, String jsonPointer) {
        if (jsonPointer == null || jsonPointer.isEmpty()) return root;
        if (!jsonPointer.startsWith("/")) throw new GraderInputException("json_pointer must start with /");
        JsonNode node = root;
        for (var token : jsonPointer.substring(1).split("/", -1)) {
            var name = token.replace("~1", "/").replace("~0", "~");
            if (node == null) return null;
            if (node.isArray()) {
                try {
                    node = node.get(Integer.parseInt(name));
                } catch (NumberFormatException e) {
                    return null;
                }
            } else {
                node = node.get(name);
            }
        }
        return node;
    }

    public String normalize(String value, String normalization) {
        var mode = normalization == null ? "TRIM" : normalization;
        return switch (mode) {
            case "NONE" -> value;
            case "TRIM" -> value.trim();
            case "TRIM_LOWERCASE" -> value.trim().toLowerCase(Locale.ROOT);
            case "COLLAPSE_WHITESPACE" -> value.trim().replaceAll("\\s+", " ");
            default -> throw new GraderInputException("unsupported normalization: " + normalization);
        };
    }

    public void validateSuite(EvalBodies.GraderSuiteBody suite) {
        if (suite == null || suite.graders == null || suite.graders.isEmpty()) {
            throw new IllegalArgumentException("suite must contain at least one grader");
        }
        if (suite.successPolicy != null && !SUCCESS_POLICY.equals(suite.successPolicy)) {
            throw new IllegalArgumentException("unsupported success_policy: " + suite.successPolicy);
        }
        var ids = new HashMap<String, Boolean>();
        var required = 0;
        for (var grader : suite.graders) {
            if (grader == null || grader.id == null || grader.id.isBlank()) {
                throw new IllegalArgumentException("grader id is required");
            }
            if (ids.put(grader.id, Boolean.TRUE) != null) {
                throw new IllegalArgumentException("duplicate grader id: " + grader.id);
            }
            if (grader.kind == null) throw new IllegalArgumentException("grader " + grader.id + " kind is required");
            var kind = EvalEnums.GraderKind.valueOf(grader.kind);
            if (kind == EvalEnums.GraderKind.REGEX) {
                if (grader.pattern == null || grader.pattern.isBlank()) {
                    throw new IllegalArgumentException("grader " + grader.id + " regex pattern is required");
                }
                validatePattern(grader.pattern);
            }
            var mode = EvalEnums.GraderMode.valueOf(grader.mode == null ? "BOOLEAN" : grader.mode);
            if (mode == EvalEnums.GraderMode.BOUNDED_SCORE) {
                var failMax = grader.failMax == null ? 0.2 : grader.failMax;
                var passMin = grader.passMin == null ? 0.8 : grader.passMin;
                if (!(failMax >= 0 && failMax < passMin && passMin <= 1)) {
                    throw new IllegalArgumentException("grader " + grader.id + " requires 0 <= failMax < passMin <= 1");
                }
            }
            if (Boolean.TRUE.equals(grader.required)) required++;
        }
        if (required == 0) throw new IllegalArgumentException("suite must contain at least one required grader");
    }

    private void validatePattern(String pattern) {
        if (pattern.length() > MAX_PATTERN_LENGTH) {
            throw new IllegalArgumentException("regex pattern is too long");
        }
        if (NESTED_QUANTIFIER.matcher(pattern).find()) {
            throw new IllegalArgumentException("regex pattern contains a nested quantifier");
        }
        Pattern.compile(pattern);
    }

    public BodiesResult splitApplicable(EvalBodies.GraderSuiteBody suite, EvalBodies.CaseBody caseBody) {
        var applicable = applicable(suite, caseBody);
        var required = new ArrayList<EvalBodies.GraderDefinition>();
        for (var grader : applicable) {
            if (Boolean.TRUE.equals(grader.required)) required.add(grader);
        }
        return new BodiesResult(applicable, required);
    }

    private enum MatchMode {
        EXACT, CONTAINS
    }

    public static class BodiesResult {
        public final List<EvalBodies.GraderDefinition> applicable;
        public final List<EvalBodies.GraderDefinition> required;

        public BodiesResult(List<EvalBodies.GraderDefinition> applicable, List<EvalBodies.GraderDefinition> required) {
            this.applicable = applicable;
            this.required = required;
        }
    }

    private static class GraderInputException extends RuntimeException {
        @Serial
        private static final long serialVersionUID = 1L;

        GraderInputException(String message) {
            super(message);
        }
    }
}
