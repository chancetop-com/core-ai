package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import ai.core.vender.VendorManagement;
import ai.core.vender.vendors.RipgrepVendor;
import core.framework.util.Strings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * @author stephen
 */
public class GrepFileTool extends ToolCall {
    public static final String TOOL_NAME = "grep_file";
    private static final int MAX_MATCHES = 100;
    private static final int MAX_LINE_LENGTH = 2000;
    private static final int MAX_READ_LINES = 10000;

    private static final String TOOL_DESC = """
            - Fast content search tool that works with any codebase size
            - Searches file contents using regular expressions
            - Supports full regex syntax (eg. "log.*Error", "function\\s+\\w+", etc.)
            - Filter files by pattern with the include parameter (eg. "*.js", "*.{ts,tsx}")
            - Returns file paths and line numbers with at least one match sorted by modification time
            - Use this tool when you need to find files containing specific patterns
            - If you need to identify/count the number of matches within files, use the Bash tool with `rg` (ripgrep) directly. Do NOT use `grep`.
            - When you are doing an open-ended search that may require multiple rounds of globbing and grepping, use the `${tool_task}` tool instead
            """.replace("${tool_task}", TaskTool.TOOL_NAME);

    public static Builder builder() {
        return new Builder();
    }

    private static String resolveAbsolutePath(ExecutionContext context, String searchPath) {
        String result;
        if (Strings.isBlank(searchPath)) {
            result = RipGrepUtil.resolveWorkspaceDir(context, TOOL_NAME);
        } else {
            var path = Paths.get(searchPath);
            if (!path.isAbsolute()) {
                var workspace = RipGrepUtil.resolveWorkspaceDir(context, TOOL_NAME);
                result = Paths.get(workspace).resolve(searchPath).toString();
            } else {
                result = searchPath;
            }
        }
        return result;
    }

    private static String cleanPath(String file) {
        return file.replaceFirst("^\\./", "");
    }

    @Override
    public ToolCallResult execute(String arguments, ExecutionContext context) {
        return doExecute(arguments, context);
    }

    @Override
    public ToolCallResult execute(String text) {
        throw new AgentRuntimeException(TOOL_NAME, "run requires ExecutionContext");
    }

    private ToolCallResult doExecute(String text, ExecutionContext context) {
        long startTime = System.currentTimeMillis();
        try {
            return runRipgrep(text, context, startTime);
        } catch (RipGrepUtil.RipGrepTimeoutException e) {
            return ToolCallResult.failed("Ripgrep process timed out")
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return ToolCallResult.failed("Grep interrupted")
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Error executing grep: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult runRipgrep(String text, ExecutionContext context, long startTime) throws Exception {
        var argsMap = parseArguments(text);
        var pattern = getStringValue(argsMap, "pattern");
        var searchPath = getStringValue(argsMap, "path");
        var include = getStringValue(argsMap, "include");

        var absoluteSearchPath = resolveAbsolutePath(context, searchPath);

        var searchCtx = resolveSearchContext(absoluteSearchPath);
        if (searchCtx == null) {
            return ToolCallResult.failed("Error: path does not exist: " + searchPath)
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        var rgPath = VendorManagement.getInstance().getExecutablePath(RipgrepVendor.class);
        var command = buildRipGrepCommand(rgPath.toString(), pattern, include, searchCtx.file);

        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(searchCtx.cwd);
        var process = pb.start();

        var processResult = RipGrepUtil.executeProcess(process, MAX_READ_LINES, getTimeoutMs());
        int exitCode = processResult.exitCode();
        var output = processResult.output();

        if (exitCode > 2) {
            return ToolCallResult.failed("Error executing ripgrep (exit code " + exitCode + "): " + output)
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        if (output.isEmpty()) {
            return ToolCallResult.completed("No files found")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("pattern", pattern);
        }

        var result = formatResults(output, searchCtx.cwd, exitCode == 2);
        return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("pattern", pattern);
    }

    private SearchContext resolveSearchContext(String searchPath) {
        var path = Paths.get(searchPath);
        if (!Files.exists(path)) {
            return null;
        }
        if (Files.isDirectory(path)) {
            return new SearchContext(path.toFile(), null);
        }
        return new SearchContext(path.getParent().toFile(), path.getFileName().toString());
    }

    private List<String> buildRipGrepCommand(String rgPath, String pattern, String include, String file) {
        var command = new ArrayList<String>();
        command.add(rgPath);
        command.add("--no-config");
        command.add("--json");
        command.add("--hidden");
        command.add("--no-messages");
        command.add("--glob=!.git/*");

        if (!Strings.isBlank(include)) {
            command.add("--glob=" + include);
        }

        command.add("--");
        command.add(pattern);

        if (!Strings.isBlank(file)) {
            command.add(file);
        } else {
            command.add(".");
        }

        return command;
    }

    private String formatResults(String rawOutput, File cwd, boolean partial) {
        var lines = rawOutput.split("\n");
        var matches = new ArrayList<GrepMatch>();

        for (String line : lines) {
            if (line.isEmpty()) continue;
            var match = parseJsonMatch(line);
            if (match != null) {
                matches.add(match);
            }
        }

        if (matches.isEmpty()) {
            return "No files found";
        }

        var fileTime = getFileTimes(matches, cwd);

        matches.sort(Comparator.comparingLong(
                (GrepMatch m) -> fileTime.getOrDefault(m.filePath(), 0L)
        ).reversed());

        return buildOutput(matches, cwd, partial);
    }

    private GrepMatch parseJsonMatch(String line) {
        try {
            var node = JsonUtil.OBJECT_MAPPER.readTree(line);
            if (!"match".equals(node.path("type").asText())) {
                return null;
            }
            var data = node.path("data");
            var filePath = cleanPath(data.path("path").path("text").asText());
            var lineNumber = data.path("line_number").asInt();
            var text = data.path("lines").path("text").asText();
            return new GrepMatch(filePath, lineNumber, text);
        } catch (Exception e) {
            return null;
        }
    }

    private Map<String, Long> getFileTimes(List<GrepMatch> matches, File cwd) {
        var uniqueFiles = matches.stream()
                .map(GrepMatch::filePath)
                .distinct()
                .toList();

        var mTime = new ConcurrentHashMap<String, Long>(uniqueFiles.size());
        uniqueFiles.parallelStream().forEach(file -> {
            mTime.put(file, RipGrepUtil.getModifyTime(new File(cwd, file).toPath()));
        });
        return mTime;
    }

    private String buildOutput(List<GrepMatch> matches, File cwd, boolean partial) {
        int total = matches.size();
        boolean truncated = total > MAX_MATCHES;
        var displayMatches = truncated ? matches.subList(0, MAX_MATCHES) : matches;

        var output = new StringBuilder(256);
        output.append("Found ").append(total).append(" matches");
        if (truncated) {
            output.append(" (showing first ").append(MAX_MATCHES).append(')');
        }

        String currentFile = "";
        for (var match : displayMatches) {
            if (!currentFile.equals(match.filePath())) {
                if (!currentFile.isEmpty()) {
                    output.append('\n');
                }
                currentFile = match.filePath();
                var absolutePath = new File(cwd, match.filePath()).toString();
                output.append('\n').append(absolutePath).append(':');
            }
            var text = match.text().length() > MAX_LINE_LENGTH
                    ? match.text().substring(0, MAX_LINE_LENGTH) + "..."
                    : match.text();
            output.append("\n  Line ").append(match.lineNumber()).append(": ").append(text);

        }

        if (truncated) {
            output.append("\n\n(Results truncated: showing ").append(MAX_MATCHES)
                    .append(" of ").append(total).append(" matches (")
                    .append(total - MAX_MATCHES)
                    .append(" hidden). Consider using a more specific path or pattern.)");
        }

        if (partial) {
            output.append("\n\n(Some paths were inaccessible and skipped)");
        }

        return output.toString().trim();
    }

    private record SearchContext(File cwd, String file) {
    }

    private record GrepMatch(String filePath, int lineNumber, String text) {
    }

    public static class Builder extends ToolCall.Builder<Builder, GrepFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GrepFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "pattern", "The regex pattern to search for in file contents").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "path", "The directory to search in. Defaults to the current working directory."),
                    ToolCallParameters.ParamSpec.of(String.class, "include", "File pattern to include in the search (e.g. \"*.js\", \"*.{ts,tsx}\")")
            ));
            var tool = new GrepFileTool();
            build(tool);
            return tool;
        }
    }
}
