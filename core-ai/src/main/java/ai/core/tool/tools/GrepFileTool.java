package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import ai.core.utils.JsonUtil;
import ai.core.vender.VendorManagement;
import ai.core.vender.vendors.RipgrepVendor;
import com.fasterxml.jackson.databind.JsonNode;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStreamReader;
import java.util.ArrayList;
import java.util.List;

/**
 * @author stephen
 */
public class GrepFileTool extends ToolCall {
    public static final String TOOL_NAME = "grep_file";

    private static final String TOOL_DESC = """
            A powerful search tool built on ripgrep

              Usage:
              - ALWAYS use Grep for search tasks. NEVER invoke `grep` or `rg` as a Bash command. The Grep tool has been optimized for correct permissions and access.
              - Supports full regex syntax (e.g., "log.*Error", "function\\s+\\w+")
              - Filter files with glob parameter (e.g., "*.js", "**/*.tsx") or type parameter (e.g., "js", "py", "rust")
              - Output modes: "content" shows matching lines, "files_with_matches" shows only file paths (default), "count" shows match counts
              - Use Task tool for open-ended searches requiring multiple rounds
              - Pattern syntax: Uses ripgrep (not grep) - literal braces need escaping (use `interface\\{\\}` to find `interface{}` in Go code)
              - Multiline matching: By default patterns match within single lines only. For cross-line patterns like `struct \\{[\\s\\S]*?field`, use `multiline: true`

            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            // Parse input parameters
            var params = JsonUtil.OBJECT_MAPPER.readTree(text);

            // Get ripgrep executable path
            var rgPath = VendorManagement.getInstance().getExecutablePath(RipgrepVendor.class);

            // Build ripgrep command
            var command = buildRipgrepCommand(rgPath.toString(), params);

            // Execute ripgrep
            var pb = new ProcessBuilder(command);
            pb.redirectErrorStream(true);

            var process = pb.start();

            // Read output
            var output = readProcessOutput(process);

            int exitCode = process.waitFor();

            // Exit code 0 = matches found, 1 = no matches, 2+ = error
            if (exitCode > 1) {
                return ToolCallResult.failed("Error executing ripgrep (exit code " + exitCode + "): " + output)
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            if (output.isEmpty()) {
                return ToolCallResult.completed("No matches found")
                    .withDuration(System.currentTimeMillis() - startTime)
                    .withStats("pattern", params.path("pattern").asText());
            }

            return ToolCallResult.completed(output.trim())
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("pattern", params.path("pattern").asText());

        } catch (Exception e) {
            return ToolCallResult.failed("Error executing grep: " + e.getMessage())
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String readProcessOutput(Process process) throws IOException {
        var output = new StringBuilder();
        try (var reader = new BufferedReader(new InputStreamReader(process.getInputStream()))) {
            String line = reader.readLine();
            while (line != null) {
                output.append(line).append('\n');
                line = reader.readLine();
            }
        }
        return output.toString();
    }

    private List<String> buildRipgrepCommand(String rgPath, JsonNode params) {
        var command = new ArrayList<String>();
        command.add(rgPath);
        command.add(params.path("pattern").asText());

        addOptionalSearchPath(command, params);
        addFileFilters(command, params);
        addSearchOptions(command, params);
        addOutputMode(command, params);

        return command;
    }

    private void addOptionalSearchPath(List<String> command, JsonNode params) {
        if (params.has("path") && !params.get("path").isNull()) {
            command.add(params.get("path").asText());
        }
    }

    private void addFileFilters(List<String> command, JsonNode params) {
        if (params.has("glob") && !params.get("glob").isNull()) {
            command.add("--glob");
            command.add(params.get("glob").asText());
        }

        if (params.has("type") && !params.get("type").isNull()) {
            command.add("--type");
            command.add(params.get("type").asText());
        }
    }

    private void addSearchOptions(List<String> command, JsonNode params) {
        if (params.has("-i") && params.get("-i").asBoolean()) {
            command.add("-i");
        }

        if (params.has("multiline") && params.get("multiline").asBoolean()) {
            command.add("-U");
            command.add("--multiline-dotall");
        }
    }

    private void addOutputMode(List<String> command, JsonNode params) {
        var outputMode = params.has("output_mode") ? params.get("output_mode").asText() : "files_with_matches";
        switch (outputMode) {
            case "files_with_matches":
                command.add("-l");
                break;
            case "count":
                command.add("--count");
                break;
            case "content":
                addContentModeOptions(command, params);
                break;
            default:
                // Default to files_with_matches
                command.add("-l");
                break;
        }
    }

    private void addContentModeOptions(List<String> command, JsonNode params) {
        boolean showLineNumbers = !params.has("-n") || params.get("-n").asBoolean();
        if (showLineNumbers) {
            command.add("-n");
        }

        if (params.has("-A") && !params.get("-A").isNull()) {
            command.add("-A");
            command.add(params.get("-A").asText());
        }
        if (params.has("-B") && !params.get("-B").isNull()) {
            command.add("-B");
            command.add(params.get("-B").asText());
        }
        if (params.has("-C") && !params.get("-C").isNull()) {
            command.add("-C");
            command.add(params.get("-C").asText());
        }
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
                    ToolCallParameters.ParamSpec.of(String.class, "pattern", "The regular expression pattern to search for in file contents").required(),
                    ToolCallParameters.ParamSpec.of(String.class, "path", "File or directory to search in (rg PATH). Defaults to current working directory."),
                    ToolCallParameters.ParamSpec.of(String.class, "glob", "Glob pattern to filter files (e.g. \"*.js\", \"*.{ts,tsx}\") - maps to rg --glob"),
                    ToolCallParameters.ParamSpec.of(String.class, "output_mode", "Output mode: \"content\" shows matching lines (supports -A/-B/-C context, -n line numbers, head_limit), \"files_with_matches\" shows file paths (supports head_limit), \"count\" shows match counts (supports head_limit). Defaults to \"files_with_matches\".").enums(List.of("content", "files_with_matches", "count")),
                    ToolCallParameters.ParamSpec.of(Integer.class, "-B", "Number of lines to show before each match (rg -B). Requires output_mode: \"content\", ignored otherwise."),
                    ToolCallParameters.ParamSpec.of(Integer.class, "-A", "Number of lines to show after each match (rg -A). Requires output_mode: \"content\", ignored otherwise."),
                    ToolCallParameters.ParamSpec.of(Integer.class, "-C", "Number of lines to show before and after each match (rg -C). Requires output_mode: \"content\", ignored otherwise."),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "-n", "Show line numbers in output (rg -n). Requires output_mode: \"content\", ignored otherwise. Defaults to true."),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "-i", "Case insensitive search (rg -i)"),
                    ToolCallParameters.ParamSpec.of(String.class, "type", "File type to search (rg --type). Common types: js, py, rust, go, java, etc. More efficient than include for standard file types."),
                    ToolCallParameters.ParamSpec.of(Integer.class, "head_limit", "Limit output to first N lines/entries, equivalent to \"| head -N\". Works across all output modes: content (limits output lines), files_with_matches (limits file paths), count (limits count entries). Defaults based on \"cap\" experiment value: 0 (unlimited), 20, or 100."),
                    ToolCallParameters.ParamSpec.of(Boolean.class, "multiline", "Enable multiline mode where . matches newlines and patterns can span lines (rg -U --multiline-dotall). Default: false.")
                    ));
            var tool = new GrepFileTool();
            build(tool);
            return tool;
        }
    }
}
