package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import ai.core.skill.SkillMetadata;
import ai.core.skill.SkillRegistry;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;
import java.util.concurrent.atomic.AtomicInteger;

/**
 * Agent-callable tool for reporting friction ("papercuts") encountered while using a skill
 * attached to the current session. Restricted to mounted skills and rate-limited to 2 reports
 * per skill per session.
 *
 * @author stephen
 */
public class ReportSkillPapercutTool extends ToolCall {
    public static final String TOOL_NAME = "report_skill_papercut";
    public static final String ERROR_NOT_ATTACHED = "SKILL_NOT_ATTACHED";
    public static final String ERROR_RATE_LIMITED = "PAPERCUT_RATE_LIMITED";
    public static final int MAX_REPORTS_PER_SKILL_PER_SESSION = 2;
    public static final int MIN_SUMMARY_LENGTH = 10;
    public static final int MAX_SUMMARY_LENGTH = 200;

    public static final Set<String> VALID_CATEGORIES = Set.of(
            "TRIGGER_MISS",
            "RESOURCE_READ_FAIL",
            "SCRIPT_ERROR",
            "OUTPUT_UNUSABLE",
            "BROKEN_COMMAND",
            "MISSING_PERMISSION",
            "OUTDATED_DOC",
            "AMBIGUOUS_STEP",
            "PLATFORM_INCOMPATIBLE",
            "OTHER"
    );

    private static final String DESC = """
            Report a reproducible friction issue ("papercut") for a skill attached to this session.
            Only skills attached to the current session may be reported (at most 2 reports per skill per session).
            """;

    public static Builder builder() {
        return new Builder();
    }

    private SkillRegistry registry;
    private PapercutSubmitter submitter;
    private final ConcurrentMap<String, AtomicInteger> sessionSkillCounts = new ConcurrentHashMap<>();

    @Override
    public ToolCallResult execute(String arguments) {
        return execute(arguments, null);
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        long start = System.currentTimeMillis();
        try {
            var args = parseArguments(arguments);
            var skillName = resolveSkillName(args);
            var rawCategory = getStringValue(args, "category");
            var summary = getStringValue(args, "summary");
            var observedError = getStringValue(args, "observed_error");
            var suggestedFix = getStringValue(args, "suggested_fix");

            if (skillName == null) {
                return ToolCallResult.failed("'skill' is required").withDuration(System.currentTimeMillis() - start);
            }
            var skill = registry != null ? registry.find(skillName) : null;
            if (skill == null) {
                return ToolCallResult.failed(ERROR_NOT_ATTACHED + ": Skill '" + skillName
                                + "' is not attached to this session.")
                        .withDuration(System.currentTimeMillis() - start);
            }
            var category = normalizeCategory(rawCategory);
            if (category == null) {
                return ToolCallResult.failed("Invalid category '" + rawCategory + "'. Allowed: " + VALID_CATEGORIES)
                        .withDuration(System.currentTimeMillis() - start);
            }
            var trimmedSummary = summary == null ? "" : summary.trim();
            if (trimmedSummary.length() < MIN_SUMMARY_LENGTH || trimmedSummary.length() > MAX_SUMMARY_LENGTH) {
                return ToolCallResult.failed("summary must be between " + MIN_SUMMARY_LENGTH + " and "
                                + MAX_SUMMARY_LENGTH + " characters")
                        .withDuration(System.currentTimeMillis() - start);
            }
            if (!tryAcquireSlot(context, skill)) {
                return ToolCallResult.failed(ERROR_RATE_LIMITED + ": Maximum of "
                                + MAX_REPORTS_PER_SKILL_PER_SESSION
                                + " papercut reports per skill per session exceeded for '"
                                + skill.getQualifiedName() + "'.")
                        .withDuration(System.currentTimeMillis() - start);
            }
            var submission = new PapercutSubmission(skill, category, trimmedSummary, observedError, suggestedFix,
                    context != null ? context.getUserId() : null, context != null ? context.getSessionId() : null);
            var confirmation = submitter.submit(submission);
            return ToolCallResult.completed(confirmation != null ? confirmation
                            : "Papercut recorded for " + skill.getQualifiedName() + " (" + category + ").")
                    .withDuration(System.currentTimeMillis() - start);
        } catch (Exception e) {
            return ToolCallResult.failed("Failed: " + e.getMessage())
                    .withDuration(System.currentTimeMillis() - start);
        }
    }

    private String resolveSkillName(Map<String, Object> args) {
        var name = getStringValue(args, "skill");
        if (name == null || name.isBlank()) {
            name = getStringValue(args, "skill_name");
        }
        return name == null || name.isBlank() ? null : name.trim();
    }

    private boolean tryAcquireSlot(ExecutionContext context, SkillMetadata skill) {
        var sessionId = context != null && context.getSessionId() != null ? context.getSessionId() : "default";
        var rateKey = sessionId + "\0" + skill.getQualifiedName();
        var counter = sessionSkillCounts.computeIfAbsent(rateKey, ignored -> new AtomicInteger(0));
        if (counter.incrementAndGet() > MAX_REPORTS_PER_SKILL_PER_SESSION) {
            counter.decrementAndGet();
            return false;
        }
        return true;
    }

    private String normalizeCategory(String raw) {
        if (raw == null || raw.isBlank()) return null;
        var normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return VALID_CATEGORIES.contains(normalized) ? normalized : null;
    }

    @FunctionalInterface
    public interface PapercutSubmitter {
        String submit(PapercutSubmission submission);
    }

    public record PapercutSubmission(
            SkillMetadata skill,
            String category,
            String summary,
            String observedError,
            String suggestedFix,
            String reporterUserId,
            String sessionId
    ) {
    }

    public static class Builder extends ToolCall.Builder<Builder, ReportSkillPapercutTool> {
        private SkillRegistry registry;
        private PapercutSubmitter submitter;

        @Override
        protected Builder self() {
            return this;
        }

        public Builder registry(SkillRegistry registry) {
            this.registry = registry;
            return this;
        }

        public Builder submitter(PapercutSubmitter submitter) {
            this.submitter = submitter;
            return this;
        }

        public ReportSkillPapercutTool build() {
            if (registry == null || submitter == null) {
                throw new IllegalStateException("registry and submitter are required");
            }
            this.name(TOOL_NAME);
            this.description(DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "skill", "Attached skill name or qualified name").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "category", "Friction category")
                            .enums(List.of("TRIGGER_MISS", "RESOURCE_READ_FAIL", "SCRIPT_ERROR", "OUTPUT_UNUSABLE",
                                    "BROKEN_COMMAND", "MISSING_PERMISSION", "OUTDATED_DOC", "AMBIGUOUS_STEP",
                                    "PLATFORM_INCOMPATIBLE", "OTHER"))
                            .required(),
                    ToolCallParameters.ParamSpec.of(String.class, "summary", "Concise summary (10-200 chars)").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "observed_error", "Optional error excerpt (<= 1000 chars)"),
                    ToolCallParameters.ParamSpec.of(String.class, "suggested_fix", "Optional suggested fix (<= 500 chars)")
            ));
            var tool = new ReportSkillPapercutTool();
            build(tool);
            tool.registry = this.registry;
            tool.submitter = this.submitter;
            return tool;
        }
    }
}
