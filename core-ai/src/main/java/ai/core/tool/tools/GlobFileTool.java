package ai.core.tool.tools;

import ai.core.tool.ToolCall;
import ai.core.tool.ToolCallParameters;
import ai.core.tool.ToolCallResult;
import core.framework.json.JSON;
import core.framework.util.Strings;
import org.jetbrains.annotations.NotNull;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.FileSystems;
import java.nio.file.FileVisitResult;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.PathMatcher;
import java.nio.file.Paths;
import java.nio.file.SimpleFileVisitor;
import java.nio.file.attribute.BasicFileAttributes;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

/**
 * @author stephen
 */
public class GlobFileTool extends ToolCall {
    public static final String TOOL_NAME = "glob_file";

    private static final Logger LOGGER = LoggerFactory.getLogger(GlobFileTool.class);
    private static final int MAX_RESULTS = 1000;

    private static final String TOOL_DESC = """
            - Fast file pattern matching tool that works with any codebase size

            - Supports glob patterns like "**/*.js" or "src/**/*.ts"

            - Returns matching file paths sorted by modification time

            - Use this tool when you need to find files by name patterns

            - When you are doing an open ended search that may require multiple rounds of
            globbing and grepping, use the Agent tool instead

            - You have the capability to call multiple tools in a single response. It is
            always better to speculatively perform multiple searches as a batch that are
            potentially useful.
            """;

    public static Builder builder() {
        return new Builder();
    }

    @Override
    public ToolCallResult execute(String text) {
        long startTime = System.currentTimeMillis();
        try {
            var argsMap = JSON.fromJSON(Map.class, text);
            var pattern = (String) argsMap.get("pattern");
            var path = (String) argsMap.get("path");

            if (Strings.isBlank(pattern)) {
                return ToolCallResult.failed("Error: pattern parameter is required")
                    .withDuration(System.currentTimeMillis() - startTime);
            }

            var result = globFiles(pattern, path);
            return ToolCallResult.completed(result)
                .withDuration(System.currentTimeMillis() - startTime)
                .withStats("pattern", pattern);
        } catch (Exception e) {
            var error = "Failed to parse glob file arguments: " + e.getMessage();
            LOGGER.error(error, e);
            return ToolCallResult.failed(error)
                .withDuration(System.currentTimeMillis() - startTime);
        }
    }

    private String globFiles(String pattern, String searchPath) {
        var basePath = validateAndGetBasePath(searchPath);
        if (basePath == null) {
            return searchPath == null ? "Error: Invalid path" : "Error: Directory does not exist or is not a directory: " + searchPath;
        }

        try {
            var matcher = FileSystems.getDefault().getPathMatcher("glob:" + pattern);
            var matches = new ArrayList<FileMatch>();

            var visitor = new GlobFileVisitor(basePath, matcher, matches);
            Files.walkFileTree(basePath, visitor);

            return formatResults(pattern, matches);
        } catch (IOException e) {
            var error = "Error searching files: " + e.getMessage();
            LOGGER.error(error, e);
            return error;
        }
    }

    private Path validateAndGetBasePath(String searchPath) {
        if (Strings.isBlank(searchPath)) {
            Path basePath = Paths.get(System.getProperty("user.dir"));
            LOGGER.info("Using current working directory: {}", basePath);
            return basePath;
        }

        Path basePath = Paths.get(searchPath);
        if (!Files.exists(basePath) || !Files.isDirectory(basePath)) {
            return null;
        }
        LOGGER.info("Searching in directory: {}", basePath);
        return basePath;
    }

    private String formatResults(String pattern, List<FileMatch> matches) {
        if (matches.isEmpty()) {
            LOGGER.info("No files found matching pattern: {}", pattern);
            return "No files found matching pattern: " + pattern;
        }

        matches.sort(Comparator.comparingLong(FileMatch::lastModified).reversed());

        var result = new StringBuilder(64);
        result.append("Found ").append(matches.size()).append(" file(s) matching pattern '").append(pattern).append("':\n");
        for (FileMatch match : matches) {
            result.append(match.path()).append('\n');
        }

        LOGGER.info("Found {} file(s) matching pattern: {}", matches.size(), pattern);
        return result.toString().trim();
    }

    private static class GlobFileVisitor extends SimpleFileVisitor<Path> {
        private final Path basePath;
        private final PathMatcher matcher;
        private final List<FileMatch> matches;

        GlobFileVisitor(Path basePath, PathMatcher matcher, List<FileMatch> matches) {
            this.basePath = basePath;
            this.matcher = matcher;
            this.matches = matches;
        }

        @NotNull
        @Override
        public FileVisitResult visitFile(Path file, @NotNull BasicFileAttributes attrs) {
            var relativePath = basePath.relativize(file);
            if (matcher.matches(relativePath) || matcher.matches(file.getFileName())) {
                matches.add(new FileMatch(file, attrs.lastModifiedTime().toMillis()));
            }

            if (matches.size() >= MAX_RESULTS) {
                return FileVisitResult.TERMINATE;
            }
            return FileVisitResult.CONTINUE;
        }

        @NotNull
        @Override
        public FileVisitResult visitFileFailed(Path file, IOException exc) {
            LOGGER.warn("Failed to visit file: {}, error: {}", file, exc.getMessage());
            return FileVisitResult.CONTINUE;
        }
    }

    private record FileMatch(Path path, long lastModified) {
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
