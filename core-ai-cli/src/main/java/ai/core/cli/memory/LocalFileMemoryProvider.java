package ai.core.cli.memory;

import ai.core.cli.DebugLog;
import ai.core.memory.MemoryProvider;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * @author xander
 */
public class LocalFileMemoryProvider implements MemoryProvider {

    private static final long PROJECT_MAX_BYTES = 10 * 1024L;

    private final Path globalPath;
    private final Path projectPath;

    public LocalFileMemoryProvider(Path workspace) {
        this.globalPath = Path.of(System.getProperty("user.home"), ".core-ai", "MEMORY.md");
        this.projectPath = workspace.resolve(".core-ai/MEMORY.md");
    }

    @Override
    public String load() {
        var sb = new StringBuilder(128);
        String global = readFileQuietly(globalPath);
        String project = readFileQuietly(projectPath);

        if (!global.isBlank()) {
            sb.append("--- Global Memory ---\n").append(global.strip()).append('\n');
        }
        if (!project.isBlank()) {
            if (!sb.isEmpty()) sb.append('\n');
            sb.append("--- Project Memory ---\n").append(project.strip()).append('\n');
        }
        return sb.toString();
    }

    @Override
    public String readRaw() {
        return readFileQuietly(projectPath);
    }

    @Override
    public String edit(String oldString, String newString) {
        try {
            Files.createDirectories(projectPath.getParent());
            if (!Files.exists(projectPath)) {
                Files.writeString(projectPath, "");
            }
            String content = Files.readString(projectPath);
            if (!content.contains(oldString)) {
                return "Error: old_string not found in memory file";
            }
            int occurrences = countOccurrences(content, oldString);
            if (occurrences > 1) {
                return "Error: old_string appears " + occurrences + " times, provide more context to make it unique";
            }
            String newContent = content.replace(oldString, newString);
            if (newContent.length() > PROJECT_MAX_BYTES) {
                return "Error: edit would exceed memory capacity limit (" + PROJECT_MAX_BYTES / 1024 + "KB)";
            }
            Files.writeString(projectPath, newContent);
            return "Successfully edited memory file";
        } catch (IOException e) {
            return "Error editing memory file: " + e.getMessage();
        }
    }

    public Path getProjectPath() {
        return projectPath;
    }

    public long sizeInBytes(String scope) {
        Path path = "global".equals(scope) ? globalPath : projectPath;
        try {
            return Files.exists(path) ? Files.size(path) : 0;
        } catch (IOException e) {
            return 0;
        }
    }

    private int countOccurrences(String text, String substring) {
        int count = 0;
        int index = 0;
        while ((index = text.indexOf(substring, index)) != -1) {
            count++;
            index += substring.length();
        }
        return count;
    }

    private String readFileQuietly(Path path) {
        try {
            return Files.exists(path) ? Files.readString(path) : "";
        } catch (IOException e) {
            DebugLog.log("Failed to read memory file " + path + ": " + e.getMessage());
            return "";
        }
    }
}
