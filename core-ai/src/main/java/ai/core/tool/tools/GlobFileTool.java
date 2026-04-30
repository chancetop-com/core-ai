package ai.core.tool.tools;

import ai.core.AgentRuntimeException;
import ai.core.agent.ExecutionContext;
import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.vender.VendorManagement;
import ai.core.vender.vendors.RipgrepVendor;
import core.framework.util.Strings;

import java.io.File;
import java.nio.file.Files;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * @author stephen
 */
public class GlobFileTool extends ToolCall {
    public static final String TOOL_NAME = "glob_file";

    private static final int MAX_RESULTS = 100;

    private static final String TOOL_DESC = """
            - Fast file pattern matching tool that works with any codebase size
            
            - Supports glob patterns like "**/*.js" or "src/**/*.ts"
            
            - Returns matching file paths sorted by modification time
            
            - Use this tool when you need to find files by name patterns
            
            - When you are doing an open ended search that may require multiple rounds of
            globbing and grepping, use the `${tool_task}` tool instead
            
            - You have the capability to call multiple tools in a single response. It is
            always better to speculatively perform multiple searches as a batch that are
            potentially useful.
            """.replace("${tool_task}", TaskTool.TOOL_NAME);

    public static Builder builder() {
        return new Builder();
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
            return ToolCallResult.failed("Glob interrupted")
                    .withDuration(System.currentTimeMillis() - startTime);
        } catch (Exception e) {
            return ToolCallResult.failed("Error executing glob: " + e.getMessage(), e)
                    .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private ToolCallResult runRipgrep(String text, ExecutionContext context, long startTime) throws Exception {
        var argsMap = parseArguments(text);
        var pattern = getStringValue(argsMap, "pattern");
        var searchPath = getStringValue(argsMap, "path");

        if (Strings.isBlank(pattern)) {
            return ToolCallResult.failed("Error: pattern parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        var resolvedPath = Strings.isBlank(searchPath)
                ? RipGrepUtil.resolveWorkspaceDir(context, TOOL_NAME)
                : searchPath;

        var searchDir = resolveSearchDir(resolvedPath);
        if (searchDir == null) {
            return ToolCallResult.failed("Error: path must be a directory: " + searchPath)
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        var rgPath = VendorManagement.getInstance().getExecutablePath(RipgrepVendor.class);
        var command = buildRipGrepCommand(rgPath.toString(), pattern);

        var pb = new ProcessBuilder(command);
        pb.redirectErrorStream(true);
        pb.directory(searchDir);
        var process = pb.start();
        var processResult = RipGrepUtil.executeProcess(process, MAX_RESULTS + 1, getTimeoutMs());
        int exitCode = processResult.exitCode();
        var output = processResult.output();

        if (exitCode > 1) {
            return ToolCallResult.failed("Error executing ripgrep (exit code " + exitCode + "): " + output)
                    .withDuration(System.currentTimeMillis() - startTime);
        }

        if (output.isEmpty()) {
            return ToolCallResult.completed("No files found")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("pattern", pattern);
        }

        var result = formatResults(output, searchDir);
        return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("pattern", pattern);
    }

    private List<String> buildRipGrepCommand(String rgPath, String pattern) {
        var command = new ArrayList<String>();
        command.add(rgPath);
        command.add("--no-config");
        command.add("--files");
        command.add("--hidden");
        command.add("--glob=!.git/*");
        command.add("--glob");
        command.add(pattern);
        command.add(".");
        return command;
    }

    private File resolveSearchDir(String searchPath) {
        var path = Paths.get(searchPath);
        if (!Files.exists(path) || !Files.isDirectory(path)) {
            return null;
        }
        return path.toFile();
    }

    private List<FileMatch> parseOutput(String rawOutput, File searchDir) {
        var lines = rawOutput.split("\n");
        List<FileMatch> matches = new ArrayList<>();

        for (String line : lines) {
            if (line.isEmpty()) continue;
            var absolutePath = new File(searchDir, cleanPath(line)).toPath();
            matches.add(new FileMatch(absolutePath.toString(), RipGrepUtil.getModifyTime(absolutePath)));
        }
        return matches;
    }

    private String formatResults(String rawOutput, File searchDir) {
        var allMatches = parseOutput(rawOutput, searchDir);

        if (allMatches.isEmpty()) {
            return "No files found";
        }

        allMatches.sort(Comparator.comparingLong(FileMatch::mtime).reversed());

        boolean truncated = allMatches.size() > MAX_RESULTS;
        var displayMatches = truncated ? allMatches.subList(0, MAX_RESULTS) : allMatches;

        var result = new StringBuilder(256);
        for (FileMatch match : displayMatches) {
            result.append(match.path()).append('\n');
        }

        if (truncated) {
            result.append("\n(Results are truncated: showing first ").append(MAX_RESULTS)
                    .append(" results. Consider using a more specific path or pattern.)");
        }

        return result.toString().trim();
    }

    private record FileMatch(String path, long mtime) {
    }

    public static class Builder extends ToolCall.Builder<Builder, GlobFileTool> {
        @Override
        protected Builder self() {
            return this;
        }

        public GlobFileTool build() {
            this.name(TOOL_NAME);
            this.description(TOOL_DESC);
            this.parameters(ToolCallParameters.of(
                    ToolCallParameters.ParamSpec.of(String.class, "pattern", "The glob pattern to match files against").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "path", "The directory to search in. If not specified, the current working directory will be used. IMPORTANT: Omit this field to use the default directory. DO NOT enter \"undefined\" or \"null\" - simply omit it for the default behavior. Must be a valid directory path if provided.")
            ));
            var tool = new GlobFileTool();
            build(tool);
            return tool;
        }
    }
}
