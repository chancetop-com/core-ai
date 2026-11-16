package ai.core.server.skill.quality;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Static capability scanner. It reads Python and shell resources plus executable code fences. It never imports
 * or runs anything: only literal call shapes are resolved, and anything that depends on a runtime value is
 * reported as dynamic so the gate cannot pass it by accident.
 *
 * @author core-ai
 */
public final class SkillPermissionAnalyzer {
    private static final Set<String> PYTHON = Set.of("python", "py", "python3");
    private static final Set<String> SHELL = Set.of("bash", "sh", "shell", "powershell", "ps1", "zsh");

    private static final Pattern SESSION_ALIAS = Pattern.compile("\\b([A-Za-z_]\\w*)\\s*=\\s*(?:async_session|session)\\s*\\(");
    private static final Pattern NAMESPACE_ALIAS = Pattern.compile(
        "\\b([A-Za-z_]\\w*)\\s*=\\s*(?:[A-Za-z_]\\w*)\\.(mcp|api|llm_call|agent|builtin|dataset)((?:\\.[A-Za-z0-9_-]+|\\[[^\\]]*])*)");
    private static final Pattern ROOT_CHAIN = Pattern.compile(
        "\\b([A-Za-z_]\\w*)\\.(mcp|api|llm_call|agent|builtin|dataset)((?:\\.[A-Za-z0-9_-]+|\\[[^\\]]*])*)\\s*\\(");
    private static final Pattern ALIAS_CHAIN = Pattern.compile(
        "\\b([A-Za-z_]\\w*)((?:\\.[A-Za-z0-9_-]+|\\[[^\\]]*])+)\\s*\\(");
    private static final Pattern TOOL_CALL = Pattern.compile("\\b([A-Za-z_]\\w*)\\.tool\\s*\\(\\s*([^)]*)\\)");
    private static final Pattern FILES_CALL = Pattern.compile("\\b([A-Za-z_]\\w*)\\.files\\.([A-Za-z_]\\w*)\\s*\\(");
    private static final Pattern SANDBOX_CALL = Pattern.compile("core-ai-sandbox\\s+call\\s+([^\\s\"']+)");
    private static final Pattern CLI_MCP_CALL = Pattern.compile("core-ai-cli\\s+mcp\\s+call\\s+([^\\s\"']+)");
    private static final Pattern CLI_API_CALL = Pattern.compile("core-ai-cli\\s+api-tool\\s+call\\s+([^\\s\"']+)");
    private static final Pattern CLI_AGENT_CALL = Pattern.compile("core-ai-cli\\s+agent\\s+run\\s+([^\\s\"']+)");
    private static final Pattern LITERAL = Pattern.compile("^[\"']([^\"']+)[\"']$");

    public List<SkillDetectedUsage> analyze(SkillPublishCandidate candidate) {
        var usages = new LinkedHashSet<SkillDetectedUsage>();
        for (var fence : candidate.document.fences) {
            if (PYTHON.contains(fence.language)) scanPython(fence.content, "SKILL.md", usages);
            if (SHELL.contains(fence.language)) scanShell(fence.content, "SKILL.md", usages);
        }
        for (var entry : candidate.resources.entrySet()) {
            scanResource(entry.getKey(), entry.getValue(), usages);
        }
        return List.copyOf(usages);
    }

    private void scanResource(String path, byte[] bytes, Set<SkillDetectedUsage> usages) {
        var lower = path.toLowerCase(java.util.Locale.ROOT);
        if (!(lower.endsWith(".py") || lower.endsWith(".sh") || lower.endsWith(".bash") || lower.endsWith(".ps1"))) {
            return;
        }
        var text = new String(bytes, StandardCharsets.UTF_8);
        if (lower.endsWith(".py")) scanPython(text, path, usages);
        else scanShell(text, path, usages);
    }

    private void scanPython(String text, String file, Set<SkillDetectedUsage> usages) {
        var sessionAliases = new LinkedHashSet<String>();
        var namespaceAliases = new LinkedHashMap<String, Root>();
        var lines = text.split("\n", -1);
        for (var line : lines) {
            collectSessionAliases(line, sessionAliases);
            collectNamespaceAliases(line, namespaceAliases);
        }
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            if (line.isBlank() || line.trim().startsWith("#")) continue;
            scanRootChains(line, i + 1, file, sessionAliases, usages);
            scanAliasChains(line, i + 1, file, namespaceAliases, usages);
            scanToolCalls(line, i + 1, file, sessionAliases, usages);
            scanFilesCalls(line, i + 1, file, sessionAliases, usages);
        }
    }

    private boolean isSessionRoot(String name, Set<String> aliases) {
        return aliases.contains(name) || "s".equals(name) || "sess".equals(name) || "session".equals(name);
    }

    private void collectSessionAliases(String line, Set<String> aliases) {
        var matcher = SESSION_ALIAS.matcher(line);
        while (matcher.find()) aliases.add(matcher.group(1));
    }

    private void collectNamespaceAliases(String line, Map<String, Root> aliases) {
        var matcher = NAMESPACE_ALIAS.matcher(line);
        if (!matcher.find()) return;
        var converted = convert(matcher.group(2), matcher.group(3));
        aliases.put(matcher.group(1), converted);
    }

    private void scanRootChains(String line, int lineNumber, String file, Set<String> sessionAliases,
                                Set<SkillDetectedUsage> usages) {
        var matcher = ROOT_CHAIN.matcher(line);
        while (matcher.find()) {
            if (!isSessionRoot(matcher.group(1), sessionAliases)) continue;
            var root = convert(matcher.group(2), matcher.group(3));
            addUsage(root, line, lineNumber, file, usages);
        }
    }

    private void scanAliasChains(String line, int lineNumber, String file,
                                 Map<String, Root> aliases, Set<SkillDetectedUsage> usages) {
        var matcher = ALIAS_CHAIN.matcher(line);
        while (matcher.find()) {
            var root = aliases.get(matcher.group(1));
            if (root == null) continue;
            var chain = convertSegments(matcher.group(2));
            if (matcher.start(1) > 0 && line.charAt(matcher.start(1) - 1) == '.') continue;
            var path = new ArrayList<>(root.path());
            path.addAll(chain.path());
            addUsage(new Root(root.kind(), path, chain.dynamic()), line, lineNumber, file, usages);
        }
    }

    private void scanToolCalls(String line, int lineNumber, String file, Set<String> sessionAliases,
                               Set<SkillDetectedUsage> usages) {
        var matcher = TOOL_CALL.matcher(line);
        while (matcher.find()) {
            if (!isSessionRoot(matcher.group(1), sessionAliases)) continue;
            var argument = matcher.group(2).trim();
            var literal = LITERAL.matcher(argument);
            if (literal.matches()) {
                var name = literal.group(1);
                var path = List.of(SkillCapabilityCatalog.canonical(name));
                usages.add(new SkillDetectedUsage("tool", path, file, lineNumber, false, snippet(line)));
            } else {
                usages.add(new SkillDetectedUsage("tool", List.of(), file, lineNumber, true, snippet(line)));
            }
        }
    }

    private void scanFilesCalls(String line, int lineNumber, String file, Set<String> sessionAliases,
                                Set<SkillDetectedUsage> usages) {
        var matcher = FILES_CALL.matcher(line);
        while (matcher.find()) {
            if (!isSessionRoot(matcher.group(1), sessionAliases)) continue;
            var operation = matcher.group(2);
            if ("publish".equals(operation)) {
                usages.add(new SkillDetectedUsage("tool", List.of("submit-artifacts"), file, lineNumber, false, snippet(line)));
            } else {
                usages.add(new SkillDetectedUsage("files", List.of(operation), file, lineNumber, true, snippet(line)));
            }
        }
    }

    private void scanShell(String text, String file, Set<SkillDetectedUsage> usages) {
        var lines = text.split("\n", -1);
        for (int i = 0; i < lines.length; i++) {
            var line = lines[i];
            addShellMatch(SANDBOX_CALL.matcher(line), "sandbox", line, i + 1, file, usages);
            addShellMatch(CLI_MCP_CALL.matcher(line), "mcp", line, i + 1, file, usages);
            addShellMatch(CLI_API_CALL.matcher(line), "api", line, i + 1, file, usages);
            addShellMatch(CLI_AGENT_CALL.matcher(line), "agent", line, i + 1, file, usages);
        }
    }

    private void addShellMatch(Matcher matcher, String kind, String line, int lineNumber, String file,
                               Set<SkillDetectedUsage> usages) {
        while (matcher.find()) {
            var value = matcher.group(1);
            var usageKind = "api".equals(kind) ? "api-tool" : "sandbox".equals(kind) ? "tool" : kind;
            if (value.startsWith("$") || value.startsWith("%")) {
                usages.add(new SkillDetectedUsage(usageKind, List.of(), file, lineNumber, true, snippet(line)));
            } else {
                var normalized = switch (kind) {
                    case "mcp" -> splitMcp(value);
                    case "api" -> splitPath(value);
                    default -> List.of(SkillCapabilityCatalog.canonical(value));
                };
                usages.add(new SkillDetectedUsage(usageKind, normalized, file, lineNumber, false, snippet(line)));
            }
        }
    }

    private List<String> splitMcp(String value) {
        var parts = splitPath(value);
        return parts.size() >= 2 ? List.of(parts.get(0), parts.get(1)) : parts;
    }

    private List<String> splitPath(String value) {
        var result = new ArrayList<String>();
        for (var part : value.split("[/:]")) {
            if (!part.isBlank()) result.add(SkillCapabilityCatalog.canonical(part));
        }
        return List.copyOf(result);
    }

    private void addUsage(Root root, String line, int lineNumber, String file, Set<SkillDetectedUsage> usages) {
        if (root.dynamic()) {
            usages.add(new SkillDetectedUsage(root.kind(), List.of(), file, lineNumber, true, snippet(line)));
        } else if ("llm_call".equals(root.kind())) {
            usages.add(new SkillDetectedUsage("llm-call", root.path(), file, lineNumber, false, snippet(line)));
        } else if ("agent".equals(root.kind())) {
            usages.add(new SkillDetectedUsage("subagent", root.path(), file, lineNumber, false, snippet(line)));
        } else if ("builtin".equals(root.kind())) {
            usages.add(new SkillDetectedUsage("tool", root.path(), file, lineNumber, false, snippet(line)));
        } else if ("api".equals(root.kind())) {
            usages.add(new SkillDetectedUsage("api-tool", root.path(), file, lineNumber, false, snippet(line)));
        } else {
            usages.add(new SkillDetectedUsage(root.kind(), root.path(), file, lineNumber, false, snippet(line)));
        }
    }

    private Root convert(String kind, String chainText) {
        var segments = convertSegments(chainText);
        return new Root(kind, segments.path(), segments.dynamic());
    }

    private Root convertSegments(String chainText) {
        var path = new ArrayList<String>();
        boolean dynamic = false;
        var matcher = Pattern.compile("\\.([A-Za-z0-9_-]+)|\\[([^\\]]*)]").matcher(chainText);
        while (matcher.find()) {
            if (matcher.group(1) != null) {
                path.add(SkillCapabilityCatalog.canonical(matcher.group(1)));
                continue;
            }
            var bracket = matcher.group(2).trim();
            var literal = LITERAL.matcher(bracket);
            if (literal.matches()) {
                path.add(SkillCapabilityCatalog.canonical(literal.group(1)));
            } else {
                dynamic = true;
            }
        }
        return new Root(null, path, dynamic);
    }

    private String snippet(String line) {
        var trimmed = line.trim();
        return trimmed.length() <= 200 ? trimmed : trimmed.substring(0, 200);
    }

    private record Root(String kind, List<String> path, boolean dynamic) {
    }
}
