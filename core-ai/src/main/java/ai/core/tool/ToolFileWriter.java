package ai.core.tool;

import ai.core.sandbox.Sandbox;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Writes a tool result either into the sandbox or into the local workspace.
 *
 * @author stephen
 */
public final class ToolFileWriter {
    public static void write(Sandbox sandbox, String path, byte[] bytes) throws Exception {
        if (sandbox != null) {
            sandbox.uploadFile(path, bytes);
            return;
        }
        var localPath = path.startsWith("/workspace/") ? path.substring("/workspace/".length()) : path;
        var target = Path.of(localPath);
        var parent = target.toAbsolutePath().getParent();
        if (parent != null) {
            Files.createDirectories(parent);
        }
        Files.write(target, bytes);
    }

    private ToolFileWriter() {
    }
}
