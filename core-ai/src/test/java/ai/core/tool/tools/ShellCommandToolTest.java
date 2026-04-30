package ai.core.tool.tools;

import ai.core.agent.ExecutionContext;
import core.framework.json.JSON;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * @author stephen
 */
class ShellCommandToolTest {
    private ShellCommandTool tool;
    private ExecutionContext ctx;

    @TempDir
    Path dir;

    @BeforeEach
    void setUp() {
        tool = ShellCommandTool.builder().build();
        ctx = ExecutionContext.builder()
                .customVariable("workspace", dir.toString())
                .build();
    }

    @Test
    void shouldBuildCorrectly() {
        assertNotNull(tool);
        assertEquals("run_bash_command", tool.getName());
        assertNotNull(tool.getDescription());
        assertNotNull(tool.getParameters());
    }

    @Test
    void shouldListDirectory() throws IOException {
        write("test1.txt", "content");
        write("test2.txt", "content");
        write("readme.md", "content");

        var result = shell(Map.of("command", "ls"));

        assertTrue(result.contains("test1.txt"));
        assertTrue(result.contains("test2.txt"));
        assertTrue(result.contains("readme.md"));
    }

    @Test
    void shouldReturnErrorForInvalidWorkspace() {
        var nonExistentDir = dir.resolve("non_existent").toString();
        var localCtx = ExecutionContext.builder()
                .customVariable("workspace", nonExistentDir)
                .build();

        var result = tool.execute(JSON.toJSON(Map.of("command", "ls")), localCtx).getResult();

        assertTrue(result.contains("Error") && result.contains("does not exist"));
    }

    @Test
    void shouldHandleEmptyOutput() {
        var command = isWindows()
                ? "cmd /c type nul > test_empty.txt"
                : "touch test_empty.txt";

        var result = shell(Map.of("command", command));

        assertTrue(result.isEmpty() || result.isBlank());
    }

    @Test
    void shouldExecuteScriptFile() throws IOException {
        var scriptContent = isWindows()
                ? "@echo off\necho HelloFromScriptFile"
                : "#!/bin/bash\necho 'HelloFromScriptFile'";
        var scriptExt = isWindows() ? ".bat" : ".sh";
        var scriptFile = dir.resolve("test_script" + scriptExt);
        Files.writeString(scriptFile, scriptContent);

        if (!isWindows()) {
            scriptFile.toFile().setExecutable(true);
        }

        var result = shell(Map.of("command", "./" + scriptFile.getFileName().toString()));

        assertTrue(result.contains("HelloFromScriptFile"));
    }

    @Test
    void shouldShowFilesWithSpacesInNames() throws IOException {
        write("file with spaces.txt", "content");

        var command = isWindows() ? "dir" : "ls -la";
        var result = shell(Map.of("command", command));

        assertTrue(result.contains("with spaces") || result.contains("file"));
    }

    @Test
    void shouldReportCommandError() {
        var result = shell(Map.of("command", "ls /nonexistent/path/no/such/file"));

        assertTrue(result.contains("exited with code") || result.contains("No such file"));
    }

    private String shell(Map<String, Object> args) {
        return tool.execute(JSON.toJSON(new HashMap<>(args)), ctx).getResult();
    }

    private void write(String relativePath, String content) throws IOException {
        var file = dir.resolve(relativePath);
        Files.createDirectories(file.getParent());
        Files.writeString(file, content);
    }

    private boolean isWindows() {
        return System.getProperty("os.name").toLowerCase(java.util.Locale.ROOT).contains("win");
    }
}
