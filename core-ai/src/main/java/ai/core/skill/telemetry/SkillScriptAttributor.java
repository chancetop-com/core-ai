package ai.core.skill.telemetry;

import ai.core.agent.ExecutionContext;
import ai.core.llm.domain.FunctionCall;
import ai.core.skill.SkillCapability;
import ai.core.skill.SkillMetadata;
import ai.core.tool.ToolCallResult;
import ai.core.tool.tools.PythonScriptTool;
import ai.core.tool.tools.ShellCommandTool;
import ai.core.utils.JsonUtil;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

/**
 * Deterministic attribution engine for skill SDK capabilities and shell/python script invocations.
 * Script calls are attributed only when the command or working directory explicitly references a
 * mounted skill's materialized directory; unrelated script errors are routed to the unattributed bucket.
 *
 * @author stephen
 */
public final class SkillScriptAttributor {
    private static final Set<String> SCRIPT_TOOL_NAMES = Set.of(
            ShellCommandTool.TOOL_NAME,
            PythonScriptTool.TOOL_NAME
    );

    public static boolean isScriptTool(String toolName) {
        return toolName != null && SCRIPT_TOOL_NAMES.contains(toolName);
    }

    public static void attributeToolCall(FunctionCall call, ExecutionContext context, ToolCallResult result,
                                         List<SkillMetadata> attachedSkills,
                                         Function<SkillMetadata, SkillTelemetryIdentity> identityResolver,
                                         SkillTelemetrySink sink) {
        if (call == null || call.function == null || sink == null) return;
        var toolName = call.function.name;
        boolean failed = result != null && result.isFailed();

        var capabilityTargets = matchCapabilities(toolName, context, attachedSkills, identityResolver);
        if (!capabilityTargets.isEmpty()) {
            for (var identity : capabilityTargets) {
                sink.recordAttributedScriptCall(identity, failed);
            }
            return;
        }

        if (!isScriptTool(toolName)) return;

        var scriptTargets = matchScriptPaths(call.function.arguments, attachedSkills, identityResolver);
        if (!scriptTargets.isEmpty()) {
            for (var identity : scriptTargets) {
                sink.recordAttributedScriptCall(identity, failed);
            }
            return;
        }

        if (failed) {
            sink.recordUnattributedScriptError();
        }
    }

    public static List<SkillTelemetryIdentity> matchCapabilities(String toolName, ExecutionContext context,
                                                                 List<SkillMetadata> attachedSkills,
                                                                 Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
        if (toolName == null || context == null) return List.of();
        var capabilities = context.getSkillCapabilities();
        if (capabilities == null || capabilities.isEmpty()) return List.of();
        var matched = new LinkedHashMap<String, SkillTelemetryIdentity>();
        for (SkillCapability cap : capabilities) {
            if (cap == null) continue;
            boolean matchesTool = cap.tool() != null && toolName.equals(cap.tool().getName());
            boolean matchesRef = toolName.equals(cap.refId());
            if (!matchesTool && !matchesRef) continue;
            var identity = resolveCapabilityIdentity(cap, attachedSkills, identityResolver);
            if (identity != null && identity.isValid()) {
                matched.putIfAbsent(identity.bucketKey(), identity);
            }
        }
        return List.copyOf(matched.values());
    }

    public static List<SkillTelemetryIdentity> matchScriptPaths(String argumentsJson,
                                                                List<SkillMetadata> attachedSkills,
                                                                Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
        if (attachedSkills == null || attachedSkills.isEmpty()) return List.of();
        var haystack = extractScriptText(argumentsJson);
        if (haystack.isEmpty()) return List.of();
        var normalizedHaystack = normalizeSlashes(haystack);

        var matched = new LinkedHashMap<String, SkillTelemetryIdentity>();
        for (var skill : attachedSkills) {
            if (skill == null) continue;
            if (!referencesSkill(normalizedHaystack, skill)) continue;
            var identity = resolveIdentity(skill, identityResolver);
            if (identity != null && identity.isValid()) {
                matched.putIfAbsent(identity.bucketKey(), identity);
            }
        }
        return List.copyOf(matched.values());
    }

    private static boolean referencesSkill(String normalizedHaystack, SkillMetadata skill) {
        var candidates = new ArrayList<String>(2);
        if (skill.getSkillDir() != null && !skill.getSkillDir().isBlank()) {
            candidates.add(normalizeSlashes(skill.getSkillDir()));
        }
        if (skill.getName() != null && !skill.getName().isBlank()) {
            candidates.add("/skill/" + skill.getName());
        }
        for (var prefix : candidates) {
            if (containsPathPrefix(normalizedHaystack, prefix)) {
                return true;
            }
        }
        return false;
    }

    private static boolean containsPathPrefix(String text, String rawPrefix) {
        var prefix = stripTrailingSlash(rawPrefix);
        if (prefix.isEmpty()) return false;
        int from = 0;
        while (true) {
            int idx = text.indexOf(prefix, from);
            if (idx < 0) return false;
            boolean leftBoundary = idx == 0 || isPathBoundaryBefore(text.charAt(idx - 1));
            int end = idx + prefix.length();
            boolean rightBoundary = end == text.length() || isPathBoundaryAfter(text.charAt(end));
            if (leftBoundary && rightBoundary) return true;
            from = idx + 1;
        }
    }

    private static boolean isPathBoundaryBefore(char c) {
        return c == '"' || c == '\'' || c == '=' || c == ':' || c == '(' || c == '[' || c == ',' || c == ';' || Character.isWhitespace(c);
    }

    private static boolean isPathBoundaryAfter(char c) {
        return c == '/' || c == '"' || c == '\'' || c == ')' || c == ']' || c == ',' || c == ';' || c == '&' || c == '|' || Character.isWhitespace(c);
    }

    private static String extractScriptText(String argumentsJson) {
        if (argumentsJson == null || argumentsJson.isBlank()) return "";
        try {
            @SuppressWarnings("unchecked")
            Map<String, Object> parsed = JsonUtil.fromJson(Map.class, argumentsJson);
            var sb = new StringBuilder(argumentsJson.length());
            appendField(sb, parsed.get("command"));
            appendField(sb, parsed.get("workspace"));
            appendField(sb, parsed.get("script_path"));
            appendField(sb, parsed.get("code"));
            return sb.toString();
        } catch (RuntimeException e) {
            return argumentsJson;
        }
    }

    private static void appendField(StringBuilder sb, Object value) {
        if (value == null) return;
        if (!sb.isEmpty()) sb.append(' ');
        sb.append(value);
    }

    private static String normalizeSlashes(String value) {
        return value.replace('\\', '/');
    }

    private static String stripTrailingSlash(String value) {
        int end = value.length();
        while (end > 1 && value.charAt(end - 1) == '/') {
            end--;
        }
        return value.substring(0, end);
    }

    private static SkillTelemetryIdentity resolveCapabilityIdentity(SkillCapability cap,
                                                                    List<SkillMetadata> attachedSkills,
                                                                    Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
        if (attachedSkills != null) {
            for (var skill : attachedSkills) {
                if (skill == null) continue;
                if (cap.qualifiedName() != null && cap.qualifiedName().equals(skill.getQualifiedName())) {
                    return resolveIdentity(skill, identityResolver);
                }
                if (cap.skillId() != null && cap.skillId().equals(skill.getSkillId())) {
                    return resolveIdentity(skill, identityResolver);
                }
            }
        }
        return new SkillTelemetryIdentity(cap.skillId(), cap.qualifiedName(), "");
    }

    private static SkillTelemetryIdentity resolveIdentity(SkillMetadata skill,
                                                          Function<SkillMetadata, SkillTelemetryIdentity> identityResolver) {
        if (identityResolver != null) {
            return identityResolver.apply(skill);
        }
        return new SkillTelemetryIdentity(skill.getSkillId(), skill.getQualifiedName(),
                skill.getDigest() == null ? "" : skill.getDigest());
    }

    private SkillScriptAttributor() {
    }
}
