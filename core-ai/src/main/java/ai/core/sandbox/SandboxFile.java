package ai.core.sandbox;

import java.nio.file.Path;

/**
 * @author xander
 */
public record SandboxFile(Path path, String fileName, String contentType, long size) {
}
